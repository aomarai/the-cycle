package dev.wibbleh.the_cycle;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.UUID;
import java.util.Objects;

/**
 * RpcHandler implements a simple plugin-message based RPC so that lobby instances can
 * forward admin actions (like triggering a cycle) to the hardcore backend.
 *
 * Protocol (BungeeCord custom plugin message channel):
 * - Channel: "TheCycleRPC" (registered as an incoming channel on hardcore)
 * - Message format (UTF-8 string payload): "rpc::<secret>::<action>::<caller_uuid>"
 *   Example: "rpc::s3cr3t::cycle-now::550e8400-e29b-41d4-a716-446655440000"
 */
public class RpcHandler implements PluginMessageListener {
    private final JavaPlugin plugin;
    private final Main main;
    private final String rpcSecret;
    private final String rpcChannel;

    /**
     * @param plugin hosting plugin
     * @param main main plugin instance
     * @param rpcSecret optional shared secret for RPC validation
     * @param rpcChannel namespaced plugin channel (eg. "thecycle:rpc")
     */
    public RpcHandler(JavaPlugin plugin, Main main, String rpcSecret, String rpcChannel) {
        this.plugin = Objects.requireNonNull(plugin);
        this.main = Objects.requireNonNull(main);
        this.rpcSecret = rpcSecret == null ? "" : rpcSecret;
        this.rpcChannel = rpcChannel == null ? "thecycle:rpc" : rpcChannel;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!this.rpcChannel.equals(channel)) return;
        if (message == null || message.length == 0) return;
        try (var in = new DataInputStream(new ByteArrayInputStream(message))) {
            String payload = in.readUTF();
            if (payload == null || payload.isEmpty()) return;
            // payload format: rpc::<secret>::<action>::<callerUUID>
            String[] parts = payload.split("::");
            if (parts.length < 3) return;
            if (!"rpc".equals(parts[0])) return;
            String secret = parts[1];
            String action = parts[2];
            String caller = parts.length >= 4 ? parts[3] : "";

            // If rpc_secret is configured, validate it
            if (!rpcSecret.isEmpty() && !rpcSecret.equals(secret)) {
                plugin.getLogger().warning("Rejected RPC with invalid secret from " + (caller.isEmpty() ? "unknown" : caller));
                return;
            }

            handleAction(action, caller);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse RPC message: " + e.getMessage());
        }
    }

    private void handleAction(String action, String callerUuid) {
        if (action == null) return;
        if ("cycle-now".equals(action)) {
            plugin.getLogger().info("Received RPC request to cycle (caller=" + callerUuid + ")");
            // Only hardcore backends should perform cycles; main.triggerCycle is safe-guarded
            main.triggerCycle();
            return;
        }
        plugin.getLogger().warning("Unknown RPC action: " + action);
    }
}
