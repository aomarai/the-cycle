package dev.wibbleh.the_cycle;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class DeathListener implements Listener {
    private static final long SHARED_DEATH_COOLDOWN_MS = 5000;
    
    private final JavaPlugin plugin;
    private final boolean enableActionbar;
    private final boolean enableSharedDeath;
    private final Map<UUID, Boolean> aliveMap;
    private final List<Map<String, Object>> deathRecap;
    private final AtomicBoolean sharedDeathInProgress = new AtomicBoolean(false);
    // Prevent repeated shared-death triggers in a short window
    private volatile long lastSharedDeathMs = 0L;

    /**
     * Create the death listener that records death recaps and triggers cycles.
     *
     * @param plugin            plugin instance for scheduling/logging
     * @param enableActionbar   whether to send an actionbar message on player death
     * @param enableSharedDeath whether a single death kills all players (shared death)
     * @param aliveMap          shared map tracking alive state per player UUID
     * @param deathRecap        shared list where death recap entries are appended
     */
    public DeathListener(JavaPlugin plugin, boolean enableActionbar, boolean enableSharedDeath, Map<UUID, Boolean> aliveMap, List<Map<String, Object>> deathRecap) {
        this.plugin = plugin;
        this.enableActionbar = enableActionbar;
        this.enableSharedDeath = enableSharedDeath;
        this.aliveMap = aliveMap;
        this.deathRecap = deathRecap;
    }

    /**
     * Handle a player's death: record a recap entry, optionally show an actionbar message
     * to other players, and when everyone is dead trigger a world cycle (on the main thread).
     *
     * This method runs on the server thread and schedules a 1-tick delayed check to allow
     * other death events to be processed first.
     */
    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPlayerDeath(PlayerDeathEvent ev) {
        var dead = ev.getEntity();
        var id = dead.getUniqueId();

        aliveMap.put(id, false);

        // Respawn dead player after 3 seconds to facilitate teleporting back to lobby server
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!dead.isOnline()) {
                plugin.getLogger().info("Player " + dead.getName() + " disconnected before they could be respawned.");
                return;
            }
            try {
                dead.spigot().respawn();
                plugin.getLogger().info("Respawned " + dead.getName() + " after hardcore mode death.");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to respawn dead player: " + e.getMessage());
            }
        }, 200L);

        // Mark this player to be moved to the lobby when they respawn (prevents them from being left behind)
        if (plugin instanceof Main m) {
            try {
                m.addPendingLobbyMove(id);
                plugin.getLogger().info("Marked player " + dead.getName() + " (" + id + ") for pending lobby move on respawn.");
            } catch (Exception ignored) {
                plugin.getLogger().warning("Failed to mark pending lobby move for player " + dead.getName() + " with ID " + id + ": " + ignored.getMessage());
            }
        }

        var entry = new HashMap<String, Object>();
        entry.put("name", dead.getName());
        entry.put("time", Instant.now().toString());
        // Get death message - the deprecated API returns a String
        @SuppressWarnings("deprecation")
        String deathCause = ev.getDeathMessage();
        if (deathCause == null || deathCause.isEmpty()) {
            deathCause = dead.getName() + " died";
        }
        entry.put("cause", deathCause);
        entry.put("location", dead.getLocation().getBlockX() + "," + dead.getLocation().getBlockY() + "," + dead.getLocation().getBlockZ());

        var drops = ev.getDrops().stream()
                .map(i -> i.getType().name() + " x" + i.getAmount())
                .toList();
        entry.put("drops", drops);

        deathRecap.add(entry);

        // Override vanilla death message to use our custom format
        @SuppressWarnings("deprecation")
        String emptyMsg = "";
        ev.setDeathMessage(emptyMsg);  // Clear vanilla death message
        
        // Send our custom death message with skulls and death reason to all players
        String deathMsg = "--------------------" + System.lineSeparator() + 
                          "☠ " + dead.getName() + " died ☠" + System.lineSeparator() + 
                          deathCause + System.lineSeparator() +
                          "--------------------";
        Component deathComponent = Component.text(deathMsg);
        Bukkit.getOnlinePlayers().forEach(p -> {
            try {
                p.sendMessage(deathComponent);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send death message to player " + p.getName() + ": " + e.getMessage());
            }
        });

        if (enableActionbar) {
            String msg = "Player " + dead.getName() + " died — " + ev.getDeathMessage();
            var comp = Component.text(msg);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(comp));
        }

        new BukkitRunnable() {
            public void run() {
                boolean anyAlive = Bukkit.getOnlinePlayers().stream()
                        .anyMatch(p -> aliveMap.getOrDefault(p.getUniqueId(), true));
                
                if (!anyAlive) {
                    // If shared-death mode is enabled, the shared-death handler will trigger the cycle.
                    if (enableSharedDeath) {
                        plugin.getLogger().info("All players dead, but shared-death is enabled; shared handler will trigger cycle.");
                        return;
                    }
                    boolean cycleIfNoPlayers = plugin.getConfig().getBoolean("behavior.cycle_when_no_online_players", false);
                    if (Bukkit.getOnlinePlayers().isEmpty() && !cycleIfNoPlayers) {
                        plugin.getLogger().info("No online players and config prohibits cycling. Skipping.");
                        return;
                    }
                    plugin.getLogger().info("All players dead — starting world cycle.");
                    // call plugin's performCycle if available via main
                    if (plugin instanceof Main m) {
                        m.triggerCycle();
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    /**
     * Optional handler that, when enabled, kills all players when one dies (shared-death)
     * and triggers a cycle. Guarded by the enableSharedDeath flag.
     */
    @EventHandler
    public void onAnyPlayerDeath(PlayerDeathEvent event) {
        if (!enableSharedDeath) return;
        // Rate-limit repeated shared-death triggers to once per 5 seconds
        long now = System.currentTimeMillis();
        if (now - lastSharedDeathMs < SHARED_DEATH_COOLDOWN_MS) return;
        if (!sharedDeathInProgress.compareAndSet(false, true)) return;
        lastSharedDeathMs = now;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                plugin.getLogger().info("A player died — shared-death handler triggered.");
                // Mark pending lobby move for all players (so dead players get moved on respawn)
                if (plugin instanceof Main m) {
                    for (var p : Bukkit.getOnlinePlayers()) {
                        try { 
                            m.addPendingLobbyMove(p.getUniqueId()); 
                            plugin.getLogger().info("Marked player " + p.getName() + " for pending lobby move (shared-death)."); 
                        } catch (Exception ignored) { 
                            plugin.getLogger().warning("Failed to mark pending move for " + p.getName()); 
                        }
                    }
                    // Do NOT forcibly kill players here — killing generates many events and can cause recursion/lag.
                    // Instead, trigger the cycle; players will be moved on respawn (or via pending move handlers).
                    plugin.getLogger().info("Triggering cycle due to shared-death.");
                    m.triggerCycle();
                }
            } finally {
                sharedDeathInProgress.set(false);
            }
        });
    }

    /**
     * When a player respawns, mark them alive and apply any pending moves that were queued while they were dead.
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var p = event.getPlayer();
        // mark alive in the shared map
        aliveMap.put(p.getUniqueId(), true);
        if (plugin instanceof Main m) {
            try {
                m.handlePlayerRespawn(p);
            } catch (Exception e) {
                plugin.getLogger().warning("Error while handling player respawn: " + e.getMessage());
            }
        }
    }

    /**
     * When a player quits, clear any pending moves so they don't persist unnecessarily.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var p = event.getPlayer();
        if (plugin instanceof Main m) {
            try {
                m.clearPendingFor(p.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().warning("Error while clearing pending moves on quit: " + e.getMessage());
            }
        }
    }
}
