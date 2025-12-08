package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for persistent RPC queue functionality.
 * Tests the complete lifecycle of failed RPC persistence and retry.
 */
class PersistentRpcQueueTest {

    @Test
    void testSaveAndLoadEmptyQueue(@TempDir Path tempDir) {
        File file = tempDir.resolve("test_rpcs.json").toFile();
        
        // Save empty list
        RpcQueueStorage.save(file, List.of());
        
        // Load should return empty list
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        assertTrue(loaded.isEmpty(), "Loaded queue should be empty");
    }

    @Test
    void testSaveAndLoadSingleRpc(@TempDir Path tempDir) {
        File file = tempDir.resolve("test_rpcs.json").toFile();
        
        byte[] payload = "{\"action\":\"cycle-now\",\"caller\":\"test\"}".getBytes();
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                payload, "cycle-now", "test", System.currentTimeMillis() / 1000, 1
        );
        
        RpcQueueStorage.save(file, List.of(rpc));
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertEquals(1, loaded.size(), "Should load one RPC");
        RpcQueueStorage.QueuedRpc loadedRpc = loaded.get(0);
        assertEquals("cycle-now", loadedRpc.action());
        assertEquals("test", loadedRpc.caller());
        assertArrayEquals(payload, loadedRpc.payload());
    }

    @Test
    void testSaveAndLoadMultipleRpcs(@TempDir Path tempDir) {
        File file = tempDir.resolve("test_rpcs.json").toFile();
        long now = System.currentTimeMillis() / 1000;
        
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
    void testExpiredRpcsFilteredOnLoad(@TempDir Path tempDir) {
        File file = tempDir.resolve("test_rpcs.json").toFile();
        long oldTimestamp = (System.currentTimeMillis() / 1000) - 100000; // Very old
        
        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(
                new RpcQueueStorage.QueuedRpc("payload".getBytes(), "action", "caller", oldTimestamp, 1)
        );
        
        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertTrue(loaded.isEmpty(), "Expired RPCs should be filtered out");
    }

    @Test
    void testQueuedRpcIncrementAttempts() {
        byte[] payload = "test".getBytes();
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                payload, "action", "caller", System.currentTimeMillis() / 1000, 1
        );
        
        RpcQueueStorage.QueuedRpc incremented = rpc.withIncrementedAttempts();
        
        assertEquals(2, incremented.attempts(), "Attempts should be incremented");
        assertEquals(rpc.action(), incremented.action());
        assertEquals(rpc.caller(), incremented.caller());
    }

    @Test
    void testQueuedRpcIsExpired() {
        long oldTimestamp = (System.currentTimeMillis() / 1000) - 100000;
        long recentTimestamp = System.currentTimeMillis() / 1000;
        
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
    void testPersistenceAcrossRestart(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpcs.json").toFile();
        
        // Simulate first run: save some RPCs
        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(
                new RpcQueueStorage.QueuedRpc(
                        "payload1".getBytes(), "cycle-now", "user1", 
                        System.currentTimeMillis() / 1000, 1
                ),
                new RpcQueueStorage.QueuedRpc(
                        "payload2".getBytes(), "cycle-now", "user2", 
                        System.currentTimeMillis() / 1000, 2
                )
        );
        RpcQueueStorage.save(file, rpcs);
        
        // Simulate restart: load RPCs
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertEquals(2, loaded.size(), "Should restore all non-expired RPCs after restart");
        assertEquals("user1", loaded.get(0).caller());
        assertEquals("user2", loaded.get(1).caller());
    }

    @Test
    void testPayloadPreservation(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpcs.json").toFile();
        
        // Test with binary payload
        byte[] binaryPayload = new byte[]{0x01, 0x02, 0x03, (byte)0xFF, (byte)0xFE};
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                binaryPayload, "test-action", "test-caller", System.currentTimeMillis() / 1000, 1
        );
        
        RpcQueueStorage.save(file, List.of(rpc));
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertEquals(1, loaded.size());
        assertArrayEquals(binaryPayload, loaded.get(0).payload(), "Binary payload should be preserved exactly");
    }

    @Test
    void testJsonStructure(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("rpcs.json").toFile();
        
        RpcQueueStorage.QueuedRpc rpc = new RpcQueueStorage.QueuedRpc(
                "test-payload".getBytes(), "cycle-now", "admin", 
                System.currentTimeMillis() / 1000, 3
        );
        
        RpcQueueStorage.save(file, List.of(rpc));
        
        // Verify JSON structure
        String json = java.nio.file.Files.readString(file.toPath());
        assertTrue(json.contains("\"action\""), "JSON should contain action field");
        assertTrue(json.contains("\"caller\""), "JSON should contain caller field");
        assertTrue(json.contains("\"payload\""), "JSON should contain payload field");
        assertTrue(json.contains("\"timestamp\""), "JSON should contain timestamp field");
        assertTrue(json.contains("\"attempts\""), "JSON should contain attempts field");
        assertTrue(json.contains("cycle-now"), "JSON should contain action value");
        assertTrue(json.contains("admin"), "JSON should contain caller value");
    }

    @Test
    void testMixedExpiredAndValidRpcs(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpcs.json").toFile();
        long oldTimestamp = (System.currentTimeMillis() / 1000) - 100000;
        long recentTimestamp = System.currentTimeMillis() / 1000;
        
        List<RpcQueueStorage.QueuedRpc> rpcs = List.of(
                new RpcQueueStorage.QueuedRpc("expired1".getBytes(), "action1", "caller1", oldTimestamp, 1),
                new RpcQueueStorage.QueuedRpc("valid1".getBytes(), "action2", "caller2", recentTimestamp, 1),
                new RpcQueueStorage.QueuedRpc("expired2".getBytes(), "action3", "caller3", oldTimestamp, 1),
                new RpcQueueStorage.QueuedRpc("valid2".getBytes(), "action4", "caller4", recentTimestamp, 1)
        );
        
        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertEquals(2, loaded.size(), "Should load only valid RPCs");
        assertEquals("action2", loaded.get(0).action());
        assertEquals("action4", loaded.get(1).action());
    }

    @Test
    void testNullPayloadHandling(@TempDir Path tempDir) {
        File file = tempDir.resolve("rpcs.json").toFile();
        
        // Create RPC with null in list (should be skipped)
        List<RpcQueueStorage.QueuedRpc> rpcs = new java.util.ArrayList<>();
        rpcs.add(new RpcQueueStorage.QueuedRpc("valid".getBytes(), "action", "caller", System.currentTimeMillis() / 1000, 1));
        rpcs.add(null);  // null entry should be skipped
        
        RpcQueueStorage.save(file, rpcs);
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(file);
        
        assertEquals(1, loaded.size(), "Null entries should be skipped");
    }
}
