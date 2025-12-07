package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class CommandHandler {
    private final JavaPlugin plugin;

    public CommandHandler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unused")
    public boolean handle(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName();
        if (name.equalsIgnoreCase("cycle")) {
            if (args.length == 0) {
                sender.sendMessage("Usage: /cycle setcycle <n> | cycle-now | status");
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
                if (plugin instanceof Main) ((Main) plugin).triggerCycle();
                sender.sendMessage("Cycling world now.");
                return true;
            }
            if (args[0].equalsIgnoreCase("status")) {
                if (plugin instanceof Main) sender.sendMessage("Cycle=" + ((Main) plugin).getCycleNumber() + " playersOnline=" + Bukkit.getOnlinePlayers().size());
                else sender.sendMessage("Cycle=unknown");
                return true;
            }
        }
        return false;
    }
}
