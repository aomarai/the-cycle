package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RpcQueueStorageTest {

    @Test
    void testSaveAndLoadEmptyQueue(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpc_queue.json").toFile();
        List<RpcQueueStorage.QueuedRpc> rpcs = new ArrayList<>();

        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);

        assertTrue(loaded.isEmpty(), "Loaded queue should be empty");
    }

    @Test
    void testSaveAndLoadSingleRpc(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpc_queue.json").toFile();
        byte[] payload = "test-payload".getBytes();
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                payload, "cycle-now", "test-caller", Instant.now().getEpochSecond(), 1
        );

        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(rpc);
        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);

        assertEquals(1, loaded.size(), "Should load one RPC");
        RpcQueueStorage.QueuedRpc loadedRpc = loaded.getFirst();
        assertEquals("cycle-now", loadedRpc.action());
        assertEquals("test-caller", loadedRpc.caller());
        assertEquals(1, loadedRpc.attempts());
        assertArrayEquals(payload, loadedRpc.payload());
    }

    @Test
    void testSaveAndLoadMultipleRpcs(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpc_queue.json").toFile();
        long now = Instant.now().getEpochSecond();

        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(
                new RpcQueueStorage.QueuedRpc("payload1".getBytes(), "action1", "caller1", now, 1),
                new RpcQueueStorage.QueuedRpc("payload2".getBytes(), "action2", "caller2", now, 2),
                new RpcQueueStorage.QueuedRpc("payload3".getBytes(), "action3", "caller3", now, 3)
        );

        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);

        assertEquals(3, loaded.size(), "Should load three RPCs");
        assertEquals("action1", loaded.get(0).action());
        assertEquals("action2", loaded.get(1).action());
        assertEquals("action3", loaded.get(2).action());
    }

    @Test
    void testLoadNonExistentFile(@TempDir Path tempDir) {
        File file = tempDir.resolve("nonexistent.json").toFile();
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);

        assertTrue(loaded.isEmpty(), "Loading nonexistent file should return empty list");
    }

    @Test
    void testLoadNullFile() {
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(null);
        assertTrue(loaded.isEmpty(), "Loading null file should return empty list");
    }

    @Test
    void testSaveNullFile() {
        RpcQueueStorage.save(null, new ArrayList<>());
        // Should not throw exception
    }

    @Test
    void testSaveNullList(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpc_queue.json").toFile();
        RpcQueueStorage.save(file, null);
        // Should not throw exception
    }

    @Test
    void testExpiredRpcIsFiltered(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpc_queue.json").toFile();
        long oldTimestamp = Instant.now().getEpochSecond() - 100000; // Very old

        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(
                new RpcQueueStorage.QueuedRpc("payload1".getBytes(), "action1", "caller1", oldTimestamp, 1)
        );

        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);

        assertTrue(loaded.isEmpty(), "Expired RPCs should be filtered out");
    }

    @Test
    void testIsExpired() {
        long oldTimestamp = Instant.now().getEpochSecond() - 100000;
        long recentTimestamp = Instant.now().getEpochSecond();

        RpcQueueStorage.QueuedRpc expired = new RpcQueueStorage.QueuedRpc(
                new byte[0], "action", "caller", oldTimestamp, 1
        );
        RpcQueueStorage.QueuedRpc recent = new RpcQueueStorage.QueuedRpc(
                new byte[0], "action", "caller", recentTimestamp, 1
        );

        assertTrue(expired.isExpired(), "Old RPC should be expired");
        assertFalse(recent.isExpired(), "Recent RPC should not be expired");
    }

    @Test
    void testWithIncrementedAttempts() {
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                "payload".getBytes(), "action", "caller", Instant.now().getEpochSecond(), 1
        );

        RpcQueueStorage.QueuedRpc incremented = rpc.withIncrementedAttempts();

        assertEquals(2, incremented.attempts(), "Attempts should be incremented");
        assertEquals(rpc.action(), incremented.action());
        assertEquals(rpc.caller(), incremented.caller());
        assertEquals(rpc.timestamp(), incremented.timestamp());
    }
}
