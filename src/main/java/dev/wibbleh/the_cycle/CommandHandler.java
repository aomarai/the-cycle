package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     *
     * @param sender command sender
     * @param cmd    command object
     * @param label  command label (unused)
     * @param args   command arguments
     * @return true when the command was handled
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return handle(sender, cmd, label, args);
    }

    @SuppressWarnings("unused")
    public boolean handle(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName();
        if (name.equalsIgnoreCase("cycle")) {
            if (args.length == 0) {
                sender.sendMessage("Usage: /cycle setcycle <n> | /cycle cycle-now | /cycle status");
                return true;
            }
            if (args[0].equalsIgnoreCase("setcycle") && args.length == 2) {
                try {
                    int n = Integer.parseInt(args[1]);
                    if (plugin instanceof Main) {
                        ((Main) plugin).setCycleNumber(n);
                    }
                    sender.sendMessage("Cycle number set to " + n);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("Invalid number.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("cycle-now")) {
                if (plugin instanceof Main) {
                    Main m = (Main) plugin;
                    if (m.isHardcoreBackend()) {
                        m.triggerCycle();
                        sender.sendMessage("Cycling world now (executed on this hardcore backend).");
                    } else {
                        boolean forwarded = m.sendRpcToHardcore("cycle-now", sender);
                        if (forwarded) sender.sendMessage("Cycle request forwarded to hardcore backend.");
                        else sender.sendMessage("Failed to forward cycle request; run /cycle on your hardcore backend.");
                    }
                } else {
                    sender.sendMessage("Cycling world now.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("status")) {
                if (plugin instanceof Main)
                    sender.sendMessage("Cycle=" + ((Main) plugin).getCycleNumber() + " playersOnline=" + Bukkit.getOnlinePlayers().size());
                else sender.sendMessage("Cycle=unknown");
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
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("cycle")) return Collections.emptyList();
        if (args.length == 1) {
            List<String> subs = Arrays.asList("setcycle", "cycle-now", "status");
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(partial)) out.add(s);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setcycle")) {
            // Suggest a few nearby numbers based on current cycle (if available)
            if (plugin instanceof Main) {
                int cur = ((Main) plugin).getCycleNumber();
                return Arrays.asList(String.valueOf(cur), String.valueOf(cur + 1), String.valueOf(cur + 2));
            }
            return Arrays.asList("1", "2", "3");
        }
        return Collections.emptyList();
    }
}
