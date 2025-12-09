package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Simple command handler that provides a minimal interface for the /cycle command.
 * The handler delegates to the Main plugin instance for operations that change state.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;

    /**
     * Create a new CommandHandler bound to the given plugin.
     *
     * @param plugin plugin instance used for callbacks
     */
    public CommandHandler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle an incoming command. Supported subcommands:
     * - setcycle <n>
     * - cycle-now
     * - status
     * - info
     * - reload
     * - queue
     * - players
     *
     * @param sender command sender
     * @param cmd    command object
     * @param label  command label (unused)
     * @param args   command arguments
     * @return true when the command was handled
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        return handle(sender, cmd, label, args);
    }

    /**
     * Check if the sender has admin permission.
     * @param sender command sender
     * @return true if sender has permission, false otherwise
     */
    private boolean checkAdminPermission(CommandSender sender) {
        if (sender == null || !sender.hasPermission("thecycle.admin")) {
            if (sender != null) {
                sender.sendMessage("§cYou do not have permission to use that command.");
            }
            return false;
        }
        return true;
    }

    @SuppressWarnings("unused")
    public boolean handle(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName();
        if (name.equalsIgnoreCase("cycle")) {
            if (args.length == 0) {
                if (sender != null) {
                    sender.sendMessage("Usage: /cycle <setcycle|cycle-now|status|info|reload|queue|players>");
                    sender.sendMessage("  setcycle <n>  - Set cycle number");
                    sender.sendMessage("  cycle-now     - Trigger immediate world cycle");
                    sender.sendMessage("  status        - Show current status");
                    sender.sendMessage("  info          - Show detailed server information");
                    sender.sendMessage("  reload        - Reload configuration");
                    sender.sendMessage("  queue         - Show RPC queue status");
                    sender.sendMessage("  players       - Show detailed player information");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("setcycle") && args.length == 2) {
                try {
                    int n = Integer.parseInt(args[1]);
                    if (plugin instanceof Main m) {
                        m.setCycleNumber(n);
                    }
                    if (sender != null) {
                        sender.sendMessage("Cycle number set to " + n);
                    }
                } catch (NumberFormatException ex) {
                    if (sender != null) {
                        sender.sendMessage("Invalid number.");
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("cycle-now")) {
                if (plugin instanceof Main m) {
                    // Permission check: only allow forwarding/executing if sender has the command permission
                    if (sender != null && !sender.hasPermission("thecycle.cycle")) {
                        sender.sendMessage("You do not have permission to use that command.");
                        return true;
                    }
                    if (m.isHardcoreBackend()) {
                        m.triggerCycle();
                        if (sender != null) {
                            sender.sendMessage("Cycling world now (executed on this hardcore backend).");
                        }
                    } else {
                        boolean forwarded = m.sendRpcToHardcore("cycle-now", sender);
                        if (sender != null) {
                            if (forwarded) {
                                // Keep the original single-line response expected by unit tests.
                                sender.sendMessage("Cycle request forwarded to hardcore backend.");
                                // Informational log: world-ready notifications will move players when available.
                                Logger.getLogger("HardcoreCycle").info("RPC forwarded to hardcore; lobby will move players when the hardcore server notifies world-ready.");
                            } else {
                                sender.sendMessage("Failed to forward cycle request; run /cycle on your hardcore backend.");
                            }
                        }
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage("Cycling world now.");
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("status")) {
                if (plugin instanceof Main m) {
                    String role = m.isHardcoreBackend() ? "hardcore" : "lobby";
                    if (sender != null) {
                        sender.sendMessage("§6=== TheCycle Status ===");
                        sender.sendMessage("§eRole: §f" + role);
                        sender.sendMessage("§eCycle: §f" + m.getCycleNumber());
                        sender.sendMessage("§ePlayers Online: §f" + Bukkit.getOnlinePlayers().size());
                        sender.sendMessage("§eAttempts Since Last Win: §f" + m.getAttemptsSinceLastWin());
                        sender.sendMessage("§eTotal Wins: §f" + m.getTotalWins());
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage("Cycle=unknown");
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("info")) {
                if (!checkAdminPermission(sender)) return true;
                if (plugin instanceof Main m) {
                    if (sender != null) {
                        sender.sendMessage("§6=== TheCycle Debug Info ===");
                        sender.sendMessage("§eServer Role: §f" + (m.isHardcoreBackend() ? "hardcore" : "lobby"));
                        sender.sendMessage("§eCycle Number: §f" + m.getCycleNumber());
                        sender.sendMessage("§eAttempts: §f" + m.getAttemptsSinceLastWin());
                        sender.sendMessage("§eTotal Wins: §f" + m.getTotalWins());
                        sender.sendMessage("§ePlayers Online: §f" + Bukkit.getOnlinePlayers().size());
                        sender.sendMessage("§eHardcore Server: §f" + m.getHardcoreServerName());
                        sender.sendMessage("§eBukkit Version: §f" + Bukkit.getVersion());
                        sender.sendMessage("§ePlugin Version: §f" + plugin.getDescription().getVersion());
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage("§cPlugin information unavailable");
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!checkAdminPermission(sender)) return true;
                plugin.reloadConfig();
                if (sender != null) {
                    sender.sendMessage("§aConfiguration reloaded successfully!");
                    sender.sendMessage("§eNote: Some settings require a server restart to take effect.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("queue")) {
                if (!checkAdminPermission(sender)) return true;
                if (plugin instanceof Main m) {
                    if (sender != null) {
                        sender.sendMessage("§6=== RPC Queue Status ===");
                        sender.sendMessage("§eOutbound Queue: §f" + m.getOutboundRpcQueueSize());
                        sender.sendMessage("§ePersistent Queue: §f" + m.getPersistentRpcQueueSize());
                        sender.sendMessage("§ePending Lobby Moves: §f" + m.getPendingLobbyMovesCount());
                        sender.sendMessage("§ePending Hardcore Moves: §f" + m.getPendingHardcoreMovesCount());
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage("§cQueue information unavailable");
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("players")) {
                if (!checkAdminPermission(sender)) return true;
                if (plugin instanceof Main m) {
                    if (sender != null) {
                        sender.sendMessage("§6=== Player Information ===");
                        sender.sendMessage("§ePlayers Online: §f" + Bukkit.getOnlinePlayers().size());
                        if (!Bukkit.getOnlinePlayers().isEmpty()) {
                            sender.sendMessage("§eOnline Players:");
                            for (var p : Bukkit.getOnlinePlayers()) {
                                String inCycle = m.isPlayerInCurrentCycle(p.getUniqueId()) ? "§a✓" : "§c✗";
                                sender.sendMessage("  §f" + p.getName() + " §7(" + p.getWorld().getName() + ") " + inCycle);
                            }
                        }
                    }
                } else {
                    if (sender != null) {
                        sender.sendMessage("§cPlayer information unavailable");
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Provide tab completion suggestions for the /cycle command.
     * Suggests subcommands and a few numeric suggestions for setcycle's second argument.
     *
     * @param sender command sender requesting completion
     * @param cmd command object
     * @param alias command alias
     * @param args current typed arguments
     * @return list of suggestions (may be empty)
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (!cmd.getName().equalsIgnoreCase("cycle")) return Collections.emptyList();
        if (args.length == 1) {
            var subs = Arrays.asList("setcycle", "cycle-now", "status", "info", "reload", "queue", "players");
            String partial = args[0].toLowerCase();
            return subs.stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setcycle")) {
            // Suggest a few nearby numbers based on current cycle (if available)
            if (plugin instanceof Main m) {
                int cur = m.getCycleNumber();
                return Arrays.asList(String.valueOf(cur), String.valueOf(cur + 1), String.valueOf(cur + 2));
            }
            return Arrays.asList("1", "2", "3");
        }
        return Collections.emptyList();
    }
}
