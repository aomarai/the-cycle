package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import net.kyori.adventure.text.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener {
    private FileConfiguration cfg;
    private boolean enableScoreboard;
    private boolean enableActionbar;
    private boolean enableBossBar;
    private BossBar bossBar;
    private Objective objective;

    // Cycle tracking
    private File cycleFile;
    private final AtomicInteger cycleNumber = new AtomicInteger(1);

    // Death recap data collected per-cycle
    private final List<Map<String, Object>> deathRecap = new ArrayList<>();

    // Webhook
    private String webhookUrl;

    // Track alive players by UUID — simple approach
    private final Map<UUID, Boolean> aliveMap = new HashMap<>();

    private WorldDeletionService worldDeletionService;
    private WebhookService webhookService;
    private DeathListener deathListener;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();

        enableScoreboard = cfg.getBoolean("features.scoreboard", true);
        enableActionbar = cfg.getBoolean("features.actionbar", true);
        enableBossBar = cfg.getBoolean("features.bossbar", true);
        webhookUrl = cfg.getString("webhook.url", "");

        cycleFile = new File(getDataFolder(), "cycles.json");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        loadCycleNumber();

        // wire services
        boolean deletePrev = cfg.getBoolean("behavior.delete_previous_worlds", true);
        boolean deferDelete = cfg.getBoolean("behavior.defer_delete_until_restart", false);
        boolean asyncDelete = cfg.getBoolean("behavior.async_delete", true);
        boolean sharedDeath = cfg.getBoolean("behavior.shared_death", false);

        worldDeletionService = new WorldDeletionService(this, deletePrev, deferDelete, asyncDelete);
        webhookService = new WebhookService(this, webhookUrl);
        deathListener = new DeathListener(this, enableActionbar, sharedDeath, aliveMap, deathRecap);
        commandHandler = new CommandHandler(this);

        worldDeletionService.processPendingDeletions();

        getServer().getPluginManager().registerEvents(deathListener, this);

        if (enableBossBar) {
            bossBar = Bukkit.createBossBar("Next world in progress...", BarColor.GREEN, BarStyle.SOLID);
        }

        if (enableScoreboard) {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            objective = scoreboard.registerNewObjective("hc_cycle", "dummy", "Hardcore Cycle");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            updateScoreboard();
        }

        Bukkit.getOnlinePlayers().forEach(p -> aliveMap.put(p.getUniqueId(), true));

        getLogger().info("HardcoreCyclePlugin enabled — cycle #" + cycleNumber.get());
    }

    @Override
    public void onDisable() {
        writeCycleFile(cycleNumber.get());
    }

    // Minimal wrappers so other classes can call into Main behaviour
    public void setCycleNumber(int n) { cycleNumber.set(n); writeCycleFile(n); updateScoreboard(); }
    public int getCycleNumber() { return cycleNumber.get(); }

    // public wrapper to allow other components to request a cycle
    public void triggerCycle() {
        performCycle();
    }

    public synchronized void performCycle() {
        int next = cycleNumber.incrementAndGet();
        updateScoreboard();
        writeCycleFile(next);

        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            webhookService.send(buildWebhookPayload(next, deathRecap));
        }

        deathRecap.clear();

        String newWorldName = "hardcore_cycle_" + next;
        getLogger().info("Generating world: " + newWorldName);
        World newWorld = null;
        try { newWorld = Bukkit.createWorld(new org.bukkit.WorldCreator(newWorldName)); } catch (Exception e) { getLogger().warning("Failed to create new world '" + newWorldName + "': " + e.getMessage()); }

        if (next > 1 && cfg.getBoolean("behavior.delete_previous_worlds", true)) {
            String prevWorldName = "hardcore_cycle_" + (next - 1);
            World prevWorld = Bukkit.getWorld(prevWorldName);
            if (prevWorld != null) {
                // SAFEGUARD: teleport any players still inside the previous world to the new world's spawn
                if (newWorld != null) {
                    try {
                        final org.bukkit.Location spawn = newWorld.getSpawnLocation();
                        if (spawn != null) {
                                                      for (Player p : prevWorld.getPlayers()) {
                                try {
                                    p.teleport(spawn);
                                } catch (Exception ex) {
                                    getLogger().warning("Failed to teleport player " + p.getName() + " out of " + prevWorldName + ": " + ex.getMessage());
                                }
                                aliveMap.put(p.getUniqueId(), true);
                            }
                            getLogger().info("Teleported players out of previous world: " + prevWorldName + " to new spawn.");
                        } else {
                            getLogger().warning("New world spawn is null; cannot safely teleport players out of " + prevWorldName);
                        }
                    } catch (Exception ex) {
                        getLogger().warning("Error while teleporting players from previous world: " + ex.getMessage());
                    }
                } else {
                    getLogger().warning("New world is null; skipping teleport of players from " + prevWorldName + ". Deletion will be deferred/attempted but may fail.");
                }

                getLogger().info("Unloading previous world: " + prevWorldName);
                boolean unloaded = Bukkit.unloadWorld(prevWorld, false);
                if (!unloaded) {
                    getLogger().warning("Failed to unload world " + prevWorldName + "; scheduling deletion fallback.");
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                } else {
                    worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
                }
            } else {
                // World file may still exist on disk even if not loaded; attempt deletion of folder anyway
                worldDeletionService.scheduleDeleteWorldFolder(prevWorldName);
            }
        }

        if (newWorld != null) {
            final org.bukkit.Location spawn = newWorld.getSpawnLocation();
            Bukkit.getOnlinePlayers().forEach(p -> {
                try { p.teleport(spawn); } catch (Exception ex) { getLogger().warning("Failed to teleport player " + p.getName() + " to new world: " + ex.getMessage()); }
                aliveMap.put(p.getUniqueId(), true);
            });
        } else {
            getLogger().warning("New world is null; skipping player teleport.");
        }

        pushBossbar("World cycled — welcome to cycle #" + next);

        getLogger().info("Cycle " + next + " complete.");
    }

    private void ensureDataFolderExists() {
        File df = getDataFolder();
        if (!df.exists()) {
            try {
                if (!df.mkdirs()) getLogger().warning("Failed to create data folder: " + df.getAbsolutePath());
            } catch (Exception e) {
                getLogger().warning("Failed to create data folder: " + e.getMessage());
            }
        }
    }

    private void loadCycleNumber() {
        try {
            ensureDataFolderExists();
            if (!cycleFile.exists()) {
                writeCycleFile(cycleNumber.get());
                return;
            }
            try (BufferedReader r = new BufferedReader(new FileReader(cycleFile))) {
                String s = r.readLine();
                if (s != null && !s.trim().isEmpty()) {
                    int n = Integer.parseInt(s.trim());
                    cycleNumber.set(n);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to read cycle file, defaulting to 1: " + e.getMessage());
        }
    }

    private void writeCycleFile(int n) {
        try {
            ensureDataFolderExists();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cycleFile))) {
                w.write(String.valueOf(n));
            }
        } catch (Exception e) {
            getLogger().severe("Unable to write cycle file: " + e.getMessage());
        }
    }

    private void updateScoreboard() {
        if (!enableScoreboard || objective == null) return;
        objective.getScore("Cycle:").setScore(cycleNumber.get());
    }

    private void pushBossbar(String message) {
        if (!enableBossBar || bossBar == null) return;
        bossBar.setTitle(message);
        // Add all online players to show
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
        });
    }

    private String buildWebhookPayload(int cycleNum, List<Map<String, Object>> recap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":null,\"embeds\":[{\"title\":\"Hardcore cycle ")
                .append(cycleNum)
                .append(" complete\",\"description\":\"Server generated new world for cycle #")
                .append(cycleNum)
                .append("\",\"fields\":[");

        for (int i = 0; i < recap.size(); i++) {
            Map<String, Object> e = recap.get(i);
            String name = (String) e.get("name");
            String time = (String) e.get("time");
            String cause = (String) e.get("cause");
            String loc = (String) e.get("location");
            @SuppressWarnings("unchecked")
            List<String> drops = (List<String>) e.get("drops");

            sb.append("{\"name\":\"").append(escape(name + " @ " + loc))
                    .append("\",\"value\":\"").append(escape("Time: " + time + "\\nCause: " + cause + "\\nDrops: " + String.join(", ", drops)))
                    .append("\",\"inline\":false}");
            if (i < recap.size() - 1) sb.append(",");
        }

        sb.append("]}]}" );
        return sb.toString();
    }

    // Basic JSON string escaper used by buildWebhookPayload
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    // Delegate command handling to CommandHandler
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return commandHandler.handle(sender, cmd, label, args);
    }
}
