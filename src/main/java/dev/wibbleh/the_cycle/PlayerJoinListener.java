package dev.wibbleh.the_cycle;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/**
 * Listener that handles player joins and prevents mid-cycle joins to hardcore.
 * Players who join during an active cycle should be kept in lobby until next cycle.
 */
public class PlayerJoinListener implements Listener {
    private final Main plugin;

    public PlayerJoinListener(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * When a player joins the server, check if they're joining during an active cycle.
     * If on hardcore server and they're not in the current cycle, move them to lobby.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        
        // Only enforce on hardcore backend
        if (!plugin.isHardcoreBackend()) {
            return;
        }

        // Check if player is in current cycle
        if (!plugin.isPlayerInCurrentCycle(p.getUniqueId())) {
            // Check if they're in a hardcore world
            String worldName = p.getWorld().getName();
            if (worldName.startsWith("hardcore_cycle_")) {
                plugin.getLogger().info("Player " + p.getName() + " joined during active cycle; moving to lobby.");
                // Schedule move to lobby on next tick to let join complete
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    p.sendMessage("§eYou joined during an active cycle. You'll join the next cycle when it starts.");
                    plugin.sendPlayerToLobby(p);
                });
            }
        }
    }

    /**
     * When a player changes to a hardcore world, track them as part of the current cycle.
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        String newWorldName = p.getWorld().getName();
        
        // If player entered a hardcore world, add them to the current cycle
        if (newWorldName.startsWith("hardcore_cycle_")) {
            plugin.addPlayerToCurrentCycle(p.getUniqueId());
            plugin.getLogger().info("Player " + p.getName() + " entered hardcore world, added to current cycle.");
        }
    }
}
