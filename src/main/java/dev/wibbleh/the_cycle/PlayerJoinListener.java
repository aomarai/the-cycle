package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/**
 * Listener that handles player joins and prevents mid-cycle joins to hardcore.
 * Players who join during an active cycle should be kept in lobby until next cycle.
 * Also handles automatic cycle starting when players are waiting in lobby.
 */
public class PlayerJoinListener implements Listener {
    private static final long AUTO_START_CHECK_DELAY_TICKS = 40; // 2 seconds
    
    private final Main plugin;

    public PlayerJoinListener(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * When a player joins the server, check if they're joining during an active cycle.
     * If on hardcore server and they're not in the current cycle, move them to lobby.
     * If on lobby server and no cycle is active, check if we should auto-start one.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var p = event.getPlayer();
        
        // Handle hardcore server join (prevent mid-cycle joins)
        if (plugin.isHardcoreBackend()) {
            // Check if player is in current cycle
            if (!plugin.isPlayerInCurrentCycle(p.getUniqueId())) {
                // Check if they're in a hardcore world
                String worldName = p.getWorld().getName();
                if (worldName.startsWith("hardcore_cycle_")) {
                    plugin.getLogger().info("Player " + p.getName() + " joined during active cycle; moving to lobby.");
                    // Schedule move to lobby on next tick to let join complete
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        p.sendMessage("§eYou joined during an active cycle. You'll join the next cycle when it starts.");
                        plugin.sendPlayerToLobby(p);
                    });
                }
            }
        } else {
            // Handle lobby server join - check if we should auto-start a cycle
            handleLobbyJoin(p);
        }
    }

    /**
     * When a player joins the lobby, check if we should auto-start a new cycle.
     * Auto-start happens if:
     * 1. Auto-start is enabled in config
     * 2. There are players waiting in lobby
     * 3. No cycle start is already pending
     */
    private void handleLobbyJoin(Player p) {
        // Only auto-start if enabled in config (default: true)
        boolean autoStart = plugin.getConfig().getBoolean("behavior.auto_start_cycles", true);
        if (!autoStart) {
            return;
        }

        // Schedule a check after a short delay to allow player to fully join
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Check if this player is still online and in lobby
            if (!p.isOnline()) return;
            
            String worldName = p.getWorld().getName();
            boolean inLobbyWorld = !worldName.startsWith("hardcore_cycle_");
            
            if (inLobbyWorld) {
                // Check if we should trigger a cycle start
                // Only auto-start if we haven't already scheduled one
                plugin.checkAndAutoStartCycle();
            }
        }, AUTO_START_CHECK_DELAY_TICKS);
    }

    /**
     * When a player changes to a hardcore world, track them as part of the current cycle.
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        var p = event.getPlayer();
        String newWorldName = p.getWorld().getName();
        
        // If player entered a hardcore world, add them to the current cycle
        if (newWorldName.startsWith("hardcore_cycle_")) {
            plugin.addPlayerToCurrentCycle(p.getUniqueId());
            plugin.getLogger().info("Player " + p.getName() + " entered hardcore world, added to current cycle.");
        }
    }
}
