package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RpcQueueStorageTest {

    @Test
    public void saveAndLoad_filtersExpiredAndPersistsValid() throws Exception {
        File tmp = File.createTempFile("rpcs", ".json");
        tmp.deleteOnExit();

        List<RpcQueueStorage.QueuedRpc> list = new ArrayList<>();
        byte[] payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        // valid entry
        list.add(new RpcQueueStorage.QueuedRpc(payload, "action1", "caller1", now, 0));
        // expired entry (very old timestamp)
        list.add(new RpcQueueStorage.QueuedRpc(payload, "action2", "caller2", 0L, 0));

        // Save should filter out expired entries
        RpcQueueStorage.save(tmp, list);

        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(tmp);
        assertNotNull(loaded);
        assertEquals(1, loaded.size(), "Only non-expired entries should be persisted and loaded");
        assertEquals("action1", loaded.get(0).action());
        assertArrayEquals(payload, loaded.get(0).payload());
    }

    @Test
    public void loadNonexistentReturnsEmpty() {
        File f = new File(System.getProperty("java.io.tmpdir"), "no-such-file-" + System.nanoTime() + ".json");
        if (f.exists()) f.delete();
        List<RpcQueueStorage.QueuedRpc> loaded = RpcQueueStorage.load(f);
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }
}

