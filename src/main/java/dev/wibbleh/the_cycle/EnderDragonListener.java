package dev.wibbleh.the_cycle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.time.Duration;

/**
 * Listener that handles ender dragon kills and updates the win/attempt tracking.
 */
public class EnderDragonListener implements Listener {
    private final Main plugin;

    public EnderDragonListener(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle ender dragon death: record win, reset attempt counter, show victory title
     */
    @EventHandler
    public void onEnderDragonDeath(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.ENDER_DRAGON) return;

        plugin.getLogger().info("Ender Dragon killed! Recording win and resetting attempt counter.");

        // Get attempt count before resetting it
        int attemptsThisWin = plugin.getAttemptsSinceLastWin();
        
        // Record the win (this will reset attempts to 0)
        plugin.recordDragonKill();

        // Show large title screen to all players
        Player killer = event.getEntity().getKiller();
        String killerName = killer != null ? killer.getName() : "Unknown Hero";
        
        Component title = Component.text("MINECRAFT BEATEN!", NamedTextColor.GOLD);
        Component subtitle = Component.text("Killed by " + killerName, NamedTextColor.YELLOW);
        
        Title titleScreen = Title.title(
            title,
            subtitle,
            Title.Times.times(
                Duration.ofMillis(500),  // fade in
                Duration.ofSeconds(5),    // stay
                Duration.ofSeconds(1)     // fade out
            )
        );

        // Show to all online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(titleScreen);
            p.sendMessage(Component.text("=".repeat(50), NamedTextColor.GOLD));
            p.sendMessage(Component.text("VICTORY! The Ender Dragon has been defeated!", NamedTextColor.GOLD));
            p.sendMessage(Component.text("Killer: " + killerName, NamedTextColor.YELLOW));
            p.sendMessage(Component.text("Attempts this win: " + attemptsThisWin, NamedTextColor.GREEN));
            p.sendMessage(Component.text("Total wins: " + plugin.getTotalWins(), NamedTextColor.GREEN));
            p.sendMessage(Component.text("=".repeat(50), NamedTextColor.GOLD));
        }
    }
}
