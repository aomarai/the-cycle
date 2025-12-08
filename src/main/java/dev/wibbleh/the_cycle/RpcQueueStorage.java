package dev.wibbleh.the_cycle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * Utility class for persisting and loading failed RPC messages to/from disk.
 * This ensures that RPC messages queued for retry are not lost on server restart.
 */
public final class RpcQueueStorage {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    private static final long MAX_AGE_SECONDS = 86400; // 24 hours
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RpcQueueStorage() {
        // Utility class
    }

    /**
     * Represents a queued RPC message with metadata for retry logic.
     */
    public record QueuedRpc(
            byte[] payload,
            String action,
            String caller,
            long timestamp,
            int attempts
    ) {
        /**
         * Check if this RPC is too old and should be discarded.
         *
         * @return true if the RPC is older than MAX_AGE_SECONDS
         */
        public boolean isExpired() {
            return (Instant.now().getEpochSecond() - timestamp) > MAX_AGE_SECONDS;
        }

        /**
         * Create a new QueuedRpc with incremented attempt count.
         *
         * @return new QueuedRpc with attempts + 1
         */
        public QueuedRpc withIncrementedAttempts() {
            return new QueuedRpc(payload, action, caller, timestamp, attempts + 1);
        }
    }

    /**
     * DTO for JSON serialization (payload is base64-encoded).
     */
    private record QueuedRpcDto(
            String payload,
            String action,
            String caller,
            long timestamp,
            int attempts
    ) {
        static QueuedRpcDto fromQueuedRpc(QueuedRpc rpc) {
            String payloadBase64 = Base64.getEncoder().encodeToString(rpc.payload);
            return new QueuedRpcDto(payloadBase64, rpc.action, rpc.caller, rpc.timestamp, rpc.attempts);
        }

        QueuedRpc toQueuedRpc() {
            byte[] payload = Base64.getDecoder().decode(this.payload);
            return new QueuedRpc(payload, action, caller, timestamp, attempts);
        }
    }

    /**
     * Save queued RPC messages to a JSON file.
     * Each RPC is stored with its payload (base64-encoded), action, caller, timestamp, and attempt count.
     *
     * @param file   target file to write to
     * @param rpcs   list of queued RPCs to persist
     */
    public static void save(File file, List<QueuedRpc> rpcs) {
        if (file == null || rpcs == null) return;

        long now = Instant.now().getEpochSecond();
        
        var dtos = rpcs.stream()
                .filter(rpc -> rpc != null)
                .filter(rpc -> {
                    // Skip expired RPCs during save (use current timestamp to avoid repeated calls)
                    long age = now - rpc.timestamp;
                    if (age > MAX_AGE_SECONDS) {
                        LOG.info("Skipping expired RPC during save: action=" + rpc.action + " age=" + age + "s");
                        return false;
                    }
                    return true;
                })
                .map(QueuedRpcDto::fromQueuedRpc)
                .toList();

        try (var writer = new FileWriter(file)) {
            GSON.toJson(dtos, writer);
            LOG.fine("Persisted " + dtos.size() + " queued RPCs to " + file.getName());
        } catch (Exception e) {
            LOG.warning("Failed to save RPC queue: " + e.getMessage());
        }
    }

    /**
     * Load queued RPC messages from a JSON file.
     *
     * @param file target file to read from
     * @return list of queued RPCs (may be empty if file doesn't exist or is invalid)
     */
    public static List<QueuedRpc> load(File file) {
        if (file == null || !file.exists()) return List.of();

        try (var reader = new FileReader(file)) {
            Type listType = new TypeToken<List<QueuedRpcDto>>(){}.getType();
            List<QueuedRpcDto> dtos = GSON.fromJson(reader, listType);
            
            if (dtos == null) {
                LOG.warning("RPC queue file is empty or invalid; ignoring.");
                return List.of();
            }

            var result = dtos.stream()
                    .map(dto -> {
                        try {
                            return dto.toQueuedRpc();
                        } catch (Exception e) {
                            LOG.warning("Failed to parse RPC queue entry: " + e.getMessage());
                            return null;
                        }
                    })
                    .filter(rpc -> {
                        if (rpc == null) return false;
                        // Filter out expired RPCs on load
                        if (rpc.isExpired()) {
                            LOG.info("Discarding expired RPC from queue: action=" + rpc.action +
                                    " age=" + (Instant.now().getEpochSecond() - rpc.timestamp) + "s");
                            return false;
                        }
                        return true;
                    })
                    .toList();
            
            LOG.info("Loaded " + result.size() + " queued RPCs from " + file.getName());
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to load RPC queue: " + e.getMessage());
            return List.of();
        }
    }
}
