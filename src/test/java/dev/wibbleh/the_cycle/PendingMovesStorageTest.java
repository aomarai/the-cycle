package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PendingMovesStorageTest {

    @Test
    public void saveAndLoad_roundtrip() throws Exception {
        File tmp = File.createTempFile("pendingmoves", ".json");
        tmp.deleteOnExit();

        Set<UUID> lobby = new HashSet<>();
        Set<UUID> hardcore = new HashSet<>();
        lobby.add(UUID.randomUUID());
        lobby.add(UUID.randomUUID());
        hardcore.add(UUID.randomUUID());

        PendingMovesStorage.save(tmp, lobby, hardcore);

        Set<UUID> loadedLobby = new HashSet<>();
        Set<UUID> loadedHardcore = new HashSet<>();
        PendingMovesStorage.load(tmp, loadedLobby, loadedHardcore);

        assertEquals(lobby.size(), loadedLobby.size());
        assertEquals(hardcore.size(), loadedHardcore.size());
        assertTrue(loadedLobby.containsAll(lobby));
        assertTrue(loadedHardcore.containsAll(hardcore));
    }
}

