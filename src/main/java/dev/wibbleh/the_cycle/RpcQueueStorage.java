package dev.wibbleh.the_cycle;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Utility class for persisting and loading failed RPC messages to/from disk.
 * This ensures that RPC messages queued for retry are not lost on server restart.
 */
public final class RpcQueueStorage {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    private static final long MAX_AGE_SECONDS = 86400; // 24 hours

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
     * Save queued RPC messages to a JSON file.
     * Each RPC is stored with its payload (base64-encoded), action, caller, timestamp, and attempt count.
     *
     * @param file   target file to write to
     * @param rpcs   list of queued RPCs to persist
     */
    @SuppressWarnings("unchecked")
    public static void save(File file, List<QueuedRpc> rpcs) {
        if (file == null || rpcs == null) return;

        JSONArray array = new JSONArray();
        long now = Instant.now().getEpochSecond();
        
        for (QueuedRpc rpc : rpcs) {
            if (rpc == null) continue;
            // Skip expired RPCs during save (use current timestamp to avoid repeated calls)
            long age = now - rpc.timestamp;
            if (age > MAX_AGE_SECONDS) {
                LOG.info("Skipping expired RPC during save: action=" + rpc.action + " age=" + age + "s");
                continue;
            }

            JSONObject obj = new JSONObject();
            // Encode payload as base64 for safe JSON storage
            obj.put("payload", java.util.Base64.getEncoder().encodeToString(rpc.payload));
            obj.put("action", rpc.action);
            obj.put("caller", rpc.caller);
            obj.put("timestamp", rpc.timestamp);
            obj.put("attempts", rpc.attempts);
            array.add(obj);
        }

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(array.toJSONString());
            LOG.fine("Persisted " + array.size() + " queued RPCs to " + file.getName());
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
        List<QueuedRpc> result = new ArrayList<>();
        if (file == null || !file.exists()) return result;

        try (FileReader reader = new FileReader(file)) {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(reader);
            if (!(parsed instanceof JSONArray)) {
                LOG.warning("RPC queue file does not contain a JSON array; ignoring.");
                return result;
            }

            JSONArray array = (JSONArray) parsed;
            for (Object item : array) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject obj = (JSONObject) item;

                try {
                    String payloadBase64 = (String) obj.get("payload");
                    String action = (String) obj.get("action");
                    String caller = (String) obj.get("caller");
                    long timestamp = obj.get("timestamp") instanceof Long
                            ? (Long) obj.get("timestamp")
                            : Long.parseLong(obj.get("timestamp").toString());
                    int attempts = obj.get("attempts") instanceof Long
                            ? ((Long) obj.get("attempts")).intValue()
                            : Integer.parseInt(obj.get("attempts").toString());

                    if (payloadBase64 == null || action == null) {
                        LOG.warning("Skipping invalid RPC entry in queue file (missing fields).");
                        continue;
                    }

                    byte[] payload = java.util.Base64.getDecoder().decode(payloadBase64);
                    QueuedRpc rpc = new QueuedRpc(payload, action, caller, timestamp, attempts);

                    // Filter out expired RPCs on load
                    if (rpc.isExpired()) {
                        LOG.info("Discarding expired RPC from queue: action=" + action +
                                " age=" + (Instant.now().getEpochSecond() - timestamp) + "s");
                        continue;
                    }

                    result.add(rpc);
                } catch (Exception e) {
                    LOG.warning("Failed to parse RPC queue entry: " + e.getMessage());
                }
            }
            LOG.info("Loaded " + result.size() + " queued RPCs from " + file.getName());
        } catch (Exception e) {
            LOG.warning("Failed to load RPC queue: " + e.getMessage());
        }

        return result;
    }
}
