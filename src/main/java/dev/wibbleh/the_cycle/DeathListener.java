package dev.wibbleh.the_cycle;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class DeathListener implements Listener {
    private final JavaPlugin plugin;
    private final boolean enableActionbar;
    private final boolean enableSharedDeath;
    private final Map<UUID, Boolean> aliveMap;
    private final List<Map<String, Object>> deathRecap;

    public DeathListener(JavaPlugin plugin, boolean enableActionbar, boolean enableSharedDeath, Map<UUID, Boolean> aliveMap, List<Map<String,Object>> deathRecap) {
        this.plugin = plugin;
        this.enableActionbar = enableActionbar;
        this.enableSharedDeath = enableSharedDeath;
        this.aliveMap = aliveMap;
        this.deathRecap = deathRecap;
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPlayerDeath(PlayerDeathEvent ev) {
        Player dead = ev.getEntity();
        UUID id = dead.getUniqueId();

        aliveMap.put(id, false);

        Map<String, Object> entry = new HashMap<>();
        entry.put("name", dead.getName());
        entry.put("time", Instant.now().toString());
        entry.put("cause", ev.getDeathMessage());
        entry.put("location", dead.getLocation().getBlockX() + "," + dead.getLocation().getBlockY() + "," + dead.getLocation().getBlockZ());

        List<String> drops = ev.getDrops().stream().map(i -> i.getType().name() + " x" + i.getAmount()).collect(Collectors.toList());
        entry.put("drops", drops);

        deathRecap.add(entry);

        if (enableActionbar) {
            String msg = "Player " + dead.getName() + " died — " + ev.getDeathMessage();
            Component comp = Component.text(msg);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(comp));
        }

        new BukkitRunnable() {
            public void run() {
                boolean anyAlive = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Boolean a = aliveMap.getOrDefault(p.getUniqueId(), true);
                    if (a) { anyAlive = true; break; }
                }
                if (!anyAlive) {
                    boolean cycleIfNoPlayers = plugin.getConfig().getBoolean("behavior.cycle_when_no_online_players", true);
                    if (Bukkit.getOnlinePlayers().isEmpty() && !cycleIfNoPlayers) {
                        plugin.getLogger().info("No online players and config prohibits cycling. Skipping.");
                        return;
                    }
                    plugin.getLogger().info("All players dead — starting world cycle.");
                    // call plugin's performCycle if available via main
                    if (plugin instanceof Main) {
                        ((Main) plugin).triggerCycle();
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onAnyPlayerDeath(PlayerDeathEvent event) {
        if (!enableSharedDeath) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("A player died — shared-death handler triggered.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { p.setHealth(0.0); } catch (Exception ignored) {}
            }
            if (plugin instanceof Main) ((Main) plugin).triggerCycle();
        });
    }
}
