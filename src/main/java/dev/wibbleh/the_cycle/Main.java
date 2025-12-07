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

/**
 * HardcoreCyclePlugin
 */
public class Main extends JavaPlugin implements Listener {
    private FileConfiguration cfg;
    private boolean enableScoreboard;
    private boolean enableActionbar;
    private boolean enableBossBar;
    private BossBar bossBar;
    private Objective objective;
    private boolean enableSharedDeath;

    // Cycle tracking
    private File cycleFile;
    private final AtomicInteger cycleNumber = new AtomicInteger(1);

    // Death recap data collected per-cycle
    private final List<Map<String, Object>> deathRecap = new ArrayList<>();

    // Webhook
    private String webhookUrl;

    // Track alive players by UUID — simple approach
    private final Map<UUID, Boolean> aliveMap = new HashMap<>();

    // Deletion behavior (read from config)
    private boolean deletePreviousWorlds = true;
    private boolean deferDeleteUntilRestart = false;
    private boolean asyncDelete = true;
    private File pendingDeletesFile;

    @Override
    @SuppressWarnings("deprecation") // registerNewObjective is deprecated in some API versions
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();

        enableScoreboard = cfg.getBoolean("features.scoreboard", true);
        enableActionbar = cfg.getBoolean("features.actionbar", true);
        enableBossBar = cfg.getBoolean("features.bossbar", true);
        enableSharedDeath = cfg.getBoolean("behavior.shared_death", false);
        webhookUrl = cfg.getString("webhook.url", "");

        // read deletion behavior from config
        deletePreviousWorlds = cfg.getBoolean("behavior.delete_previous_worlds", true);
        deferDeleteUntilRestart = cfg.getBoolean("behavior.defer_delete_until_restart", false);
        asyncDelete = cfg.getBoolean("behavior.async_delete", true);

        cycleFile = new File(getDataFolder(), "cycles.json");
        pendingDeletesFile = new File(getDataFolder(), "pending_deletes.txt");
        if (!getDataFolder().exists()) {
            boolean ok = getDataFolder().mkdirs();
            if (!ok) getLogger().warning("Failed to create plugin data folder: " + getDataFolder().getAbsolutePath());
        }
        loadCycleNumber();

        // process any pending deletions from previous runs
        processPendingDeletions();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (enableBossBar) {
            bossBar = Bukkit.createBossBar("Next world in progress...", BarColor.GREEN, BarStyle.SOLID);
        }

        if (enableScoreboard) {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            objective = scoreboard.registerNewObjective("hc_cycle", "dummy", "Hardcore Cycle");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            updateScoreboard();
        }

        // initialize aliveMap for currently-online players
        Bukkit.getOnlinePlayers().forEach(p -> aliveMap.put(p.getUniqueId(), true));

        getLogger().info("HardcoreCyclePlugin enabled — cycle #" + cycleNumber.get());
    }

    @Override
    public void onDisable() {
        saveCycleNumber();
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

    private void saveCycleNumber() {
        writeCycleFile(cycleNumber.get());
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

    @EventHandler
    @SuppressWarnings("deprecation") // getDeathMessage and sendActionBar are deprecated in some API versions
    public void onPlayerDeath(PlayerDeathEvent ev) {
        Player dead = ev.getEntity();
        UUID id = dead.getUniqueId();

        aliveMap.put(id, false);

        Map<String, Object> entry = new HashMap<>();
        entry.put("name", dead.getName());
        entry.put("time", Instant.now().toString());
        entry.put("cause", ev.getDeathMessage());
        entry.put("location", dead.getLocation().getBlockX() + "," + dead.getLocation().getBlockY() + "," + dead.getLocation().getBlockZ());

        // capture drops simple listing
        List<String> drops = ev.getDrops().stream().map(i -> i.getType().name() + " x" + i.getAmount()).collect(Collectors.toList());
        entry.put("drops", drops);

        deathRecap.add(entry);

        // actionbar example
        if (enableActionbar) {
            String msg = "Player " + dead.getName() + " died — " + ev.getDeathMessage();
            Component comp = Component.text(msg);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(comp));
        }

        // check if everyone is dead
        new BukkitRunnable() {
            public void run() {
                boolean anyAlive = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Boolean a = aliveMap.getOrDefault(p.getUniqueId(), true);
                    if (a) { anyAlive = true; break; }
                }
                // If there are zero online players, check plugin config whether to cycle anyway
                if (!anyAlive) {
                    boolean cycleIfNoPlayers = cfg.getBoolean("behavior.cycle_when_no_online_players", true);
                    if (Bukkit.getOnlinePlayers().isEmpty() && !cycleIfNoPlayers) {
                        getLogger().info("No online players and config prohibits cycling. Skipping.");
                        return;
                    }
                    getLogger().info("All players dead — starting world cycle.");
                    performCycle();
                }
            }
        }.runTaskLater(this, 1L);
    }

    private synchronized void performCycle() {
        int next = cycleNumber.incrementAndGet();
        updateScoreboard();

        // persist cycle number immediately
        writeCycleFile(next);

        // send webhook with death recap
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            sendWebhook(buildWebhookPayload(next, deathRecap));
        }

        // clear recap buffer for next cycle
        deathRecap.clear();

        // Generate a new world name and create it.
        String newWorldName = "hardcore_cycle_" + next;
        getLogger().info("Generating world: " + newWorldName);

        // create world (uses default generator and environment)
        World newWorld = null;
        try {
            newWorld = Bukkit.createWorld(new org.bukkit.WorldCreator(newWorldName));
        } catch (Exception e) {
            getLogger().warning("Failed to create new world '" + newWorldName + "': " + e.getMessage());
        }

        // Attempt to unload & delete the previous cycle world (if present)
        if (next > 1 && deletePreviousWorlds) {
            String prevWorldName = "hardcore_cycle_" + (next - 1);
            World prevWorld = Bukkit.getWorld(prevWorldName);
            if (prevWorld != null) {
                getLogger().info("Unloading previous world: " + prevWorldName);
                boolean unloaded = Bukkit.unloadWorld(prevWorld, false);
                if (!unloaded) {
                    getLogger().warning("Failed to unload world " + prevWorldName + "; skipping deletion.");
                } else {
                    // Delete the world folder from disk (respecting async/defer config)
                    scheduleDeleteWorldFolder(prevWorldName);
                }
            } else {
                // World file may still exist on disk even if not loaded; attempt deletion of folder anyway
                scheduleDeleteWorldFolder(prevWorldName);
            }
        } else if (!deletePreviousWorlds) {
            getLogger().info("Configured to keep previous worlds; skipping deletion.");
        }

        // Move any online players to the new world's spawn so they aren't kicked.
        if (newWorld != null) {
            try {
                final org.bukkit.Location spawn = newWorld.getSpawnLocation();
                // Teleport all players to the new world's spawn (assume spawn available after world creation)
                Bukkit.getOnlinePlayers().forEach(p -> {
                    try {
                        p.teleport(spawn);
                    } catch (Exception ex) {
                        getLogger().warning("Failed to teleport player " + p.getName() + " to new world: " + ex.getMessage());
                    }
                    // reset alive status
                    aliveMap.put(p.getUniqueId(), true);
                });
            } catch (Exception e) {
                getLogger().warning("Error while teleporting players to new world: " + e.getMessage());
            }
        } else {
            getLogger().warning("New world is null; skipping player teleport.");
        }

        pushBossbar("World cycled — welcome to cycle #" + next);

        getLogger().info("Cycle " + next + " complete.");
    }

    // Build a simple JSON payload for the webhook from the death recap
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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName();
        if (name.equalsIgnoreCase("cycle")) {
            if (args.length == 0) {
                sender.sendMessage("Usage: /cycle setcycle <n> | cycle-now | status");
                return true;
            }
            if (args[0].equalsIgnoreCase("setcycle") && args.length == 2) {
                try {
                    int n = Integer.parseInt(args[1]);
                    cycleNumber.set(n);
                    writeCycleFile(n);
                    updateScoreboard();
                    sender.sendMessage("Cycle number set to " + n);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("Invalid number.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("cycle-now")) {
                performCycle();
                sender.sendMessage("Cycling world now.");
                return true;
            }
            if (args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("Cycle=" + cycleNumber.get() + " playersOnline=" + Bukkit.getOnlinePlayers().size());
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onAnyPlayerDeath(PlayerDeathEvent event) {
        if (!enableSharedDeath) return;
        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info("A player died — shared-death handler triggered.");

            // Kill every online player (this will generate PlayerDeathEvent for each)
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.setHealth(0.0);
                } catch (Exception ignored) {
                    // ignore if cannot set health
                }
            }

            // Begin world cycle
            performCycle();
        });
    }

    // --- Missing helpers restored: sendWebhook, deletion scheduling and helpers ---

    private void sendWebhook(String payload) {
        // Simple asynchronous POST with basic timeouts
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) return;
        new BukkitRunnable() {
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(webhookUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    byte[] out = payload.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(out);
                        os.flush();
                    }
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) getLogger().info("Webhook POST returned " + code);
                    else getLogger().warning("Webhook POST returned non-2xx code " + code);
                } catch (Exception e) {
                    getLogger().warning("Failed to send webhook: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void scheduleDeleteWorldFolder(String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) return;
        ensureDataFolderExists();
        if (deferDeleteUntilRestart) {
            recordPendingDelete(worldName);
            getLogger().info("Deferred deletion of world '" + worldName + "' until next server start.");
            return;
        }

        if (asyncDelete) {
            new BukkitRunnable() {
                public void run() {
                    boolean ok = deleteWorldFolder(worldName);
                    if (ok) getLogger().info("Asynchronously deleted world folder: " + worldName);
                    else {
                        getLogger().warning("Asynchronous deletion failed for world: " + worldName + "; recording for restart.");
                        recordPendingDelete(worldName);
                    }
                }
            }.runTaskAsynchronously(this);
        } else {
            boolean ok = deleteWorldFolder(worldName);
            if (ok) getLogger().info("Deleted world folder: " + worldName);
            else {
                getLogger().warning("Deletion failed for world: " + worldName + ". Recording for deletion on restart.");
                recordPendingDelete(worldName);
            }
        }
    }

    private void recordPendingDelete(String worldName) {
        ensureDataFolderExists();
        if (pendingDeletesFile == null) pendingDeletesFile = new File(getDataFolder(), "pending_deletes.txt");
        try {
            // avoid duplicates
            List<String> existing = new ArrayList<>();
            if (pendingDeletesFile.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(pendingDeletesFile))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (!l.trim().isEmpty()) existing.add(l.trim());
                    }
                }
            }
            if (!existing.contains(worldName)) {
                try (BufferedWriter w = new BufferedWriter(new FileWriter(pendingDeletesFile, true))) {
                    w.write(worldName);
                    w.newLine();
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to record pending delete for " + worldName + ": " + e.getMessage());
        }
    }

    private void processPendingDeletions() {
        if (pendingDeletesFile == null || !pendingDeletesFile.exists()) return;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(pendingDeletesFile))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.trim().isEmpty()) lines.add(l.trim());
            }
        } catch (IOException e) {
            getLogger().warning("Failed to read pending deletes: " + e.getMessage());
            return;
        }
        if (lines.isEmpty()) {
            boolean delOk = pendingDeletesFile.delete();
            if (!delOk) getLogger().warning("Could not delete empty pending deletes file: " + pendingDeletesFile.getAbsolutePath());
            return;
        }
        for (String w : lines) {
            final String wn = w;
            new BukkitRunnable() {
                public void run() {
                    boolean ok = deleteWorldFolder(wn);
                    if (ok) getLogger().info("Deleted pending world folder: " + wn);
                    else getLogger().warning("Failed to delete pending world folder: " + wn);
                }
            }.runTaskAsynchronously(this);
        }
        try {
            boolean delOk = pendingDeletesFile.delete();
            if (!delOk) getLogger().warning("Could not delete pending deletes file after scheduling: " + pendingDeletesFile.getAbsolutePath());
        } catch (Exception ignored) {}
    }

    private boolean deleteWorldFolder(String worldName) {
        try {
            File worldRoot = Bukkit.getWorldContainer();
            if (worldRoot == null) return false;
            File worldFolder = new File(worldRoot, worldName);
            if (!worldFolder.exists()) return true;
            return deleteRecursively(worldFolder);
        } catch (Exception e) {
            getLogger().warning("deleteWorldFolder failed: " + e.getMessage());
            return false;
        }
    }

    private boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    if (!deleteRecursively(c)) return false;
                } else {
                    if (!c.delete()) {
                        try { boolean _w = c.setWritable(true); if (!_w) getLogger().fine("Couldn't set writable: " + c.getAbsolutePath()); } catch (Exception ignored) {}
                        if (!c.delete()) return false;
                    }
                }
            }
        }
        if (!f.delete()) {
            try { boolean _w = f.setWritable(true); if (!_w) getLogger().fine("Couldn't set writable: " + f.getAbsolutePath()); } catch (Exception ignored) {}
            return f.delete();
        }
        return true;
    }

}
