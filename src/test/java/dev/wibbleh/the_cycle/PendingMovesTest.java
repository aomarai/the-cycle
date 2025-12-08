package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PendingMovesTest {

    @Test
    void saveAndLoadPendingMoves() throws Exception {
        File tmp = Files.createTempDirectory("thecycle-test").toFile();
        tmp.deleteOnExit();
        // Prepare two UUIDs and use the PendingMovesStorage utility to save/load without a Main instance.
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        java.util.Set<UUID> lobbySet = new java.util.HashSet<>();
        java.util.Set<UUID> hardcoreSet = new java.util.HashSet<>();
        lobbySet.add(u1);
        hardcoreSet.add(u2);
        File pendingFile = new File(tmp, "pending_moves.json");
        // save
        PendingMovesStorage.save(pendingFile, lobbySet, hardcoreSet);
        // clear and load
        lobbySet.clear(); hardcoreSet.clear();
        PendingMovesStorage.load(pendingFile, lobbySet, hardcoreSet);
        assertTrue(lobbySet.contains(u1), "Lobby pending moves should contain saved UUID");
        assertTrue(hardcoreSet.contains(u2), "Hardcore pending moves should contain saved UUID");
    }
}
