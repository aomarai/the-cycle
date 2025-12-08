package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void testSaveWithNullFile() {
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        assertThrows(IllegalArgumentException.class, () -> 
            PendingMovesStorage.save(null, lobbySet, hardcoreSet));
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenFileIsNullOnLoad() {
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        assertThrows(IllegalArgumentException.class, () -> 
            PendingMovesStorage.load(null, lobbySet, hardcoreSet));
    }

    @Test
    void testLoadNonExistentFile(@TempDir Path tempDir) throws IOException {
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        lobbySet.add(UUID.randomUUID());
        hardcoreSet.add(UUID.randomUUID());
        
        File nonExistent = tempDir.resolve("non_existent.json").toFile();
        
        // Should not throw exception and should leave sets unchanged
        PendingMovesStorage.load(nonExistent, lobbySet, hardcoreSet);
        
        assertEquals(1, lobbySet.size());
        assertEquals(1, hardcoreSet.size());
    }

    @Test
    void testSaveWithNullSets(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        
        // Should not throw exception with null sets
        PendingMovesStorage.save(file, null, null);
        
        assertTrue(file.exists());
        String content = Files.readString(file.toPath());
        assertTrue(content.contains("\"lobby\":[]"));
        assertTrue(content.contains("\"hardcore\":[]"));
    }

    @Test
    void testLoadWithNullSets(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        lobbySet.add(UUID.randomUUID());
        
        PendingMovesStorage.save(file, lobbySet, hardcoreSet);
        
        // Should not throw exception with null sets
        PendingMovesStorage.load(file, null, null);
    }

    @Test
    void testSaveWithEmptySets(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        PendingMovesStorage.save(file, lobbySet, hardcoreSet);
        
        assertTrue(file.exists());
        String content = Files.readString(file.toPath());
        assertTrue(content.contains("\"lobby\":[]"));
        assertTrue(content.contains("\"hardcore\":[]"));
    }

    @Test
    void testLoadWithEmptyFile(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        assertTrue(file.createNewFile());
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        // Should not throw exception
        PendingMovesStorage.load(file, lobbySet, hardcoreSet);
        
        assertTrue(lobbySet.isEmpty());
        assertTrue(hardcoreSet.isEmpty());
    }

    @Test
    void testLoadWithInvalidJson(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("not valid json at all");
        }
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        // Should not throw exception, should handle gracefully
        PendingMovesStorage.load(file, lobbySet, hardcoreSet);
        
        assertTrue(lobbySet.isEmpty());
        assertTrue(hardcoreSet.isEmpty());
    }

    @Test
    void testLoadWithMalformedUUIDs(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{\"lobby\":[\"not-a-uuid\",\"also-not-uuid\"],\"hardcore\":[]}");
        }
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        // Should not throw exception, should skip invalid UUIDs
        PendingMovesStorage.load(file, lobbySet, hardcoreSet);
        
        assertTrue(lobbySet.isEmpty());
        assertTrue(hardcoreSet.isEmpty());
    }

    @Test
    void testLoadWithMixedValidAndInvalidUUIDs(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        UUID validUUID = UUID.randomUUID();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{\"lobby\":[\"not-a-uuid\",\"" + validUUID + "\"],\"hardcore\":[]}");
        }
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        PendingMovesStorage.load(file, lobbySet, hardcoreSet);
        
        assertEquals(1, lobbySet.size());
        assertTrue(lobbySet.contains(validUUID));
        assertTrue(hardcoreSet.isEmpty());
    }

    @Test
    void testSaveCreatesParentDirectory(@TempDir Path tempDir) throws IOException {
        File parentDir = tempDir.resolve("nested/deep/directory").toFile();
        File file = new File(parentDir, "test.json");
        
        assertFalse(parentDir.exists());
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        lobbySet.add(UUID.randomUUID());
        
        PendingMovesStorage.save(file, lobbySet, hardcoreSet);
        
        assertTrue(parentDir.exists());
        assertTrue(file.exists());
    }

    @Test
    void testMultipleUUIDs(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        UUID l3 = UUID.randomUUID();
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        
        lobbySet.add(l1);
        lobbySet.add(l2);
        lobbySet.add(l3);
        hardcoreSet.add(h1);
        hardcoreSet.add(h2);
        
        PendingMovesStorage.save(file, lobbySet, hardcoreSet);
        
        Set<UUID> loadedLobby = new java.util.HashSet<>();
        Set<UUID> loadedHardcore = new java.util.HashSet<>();
        
        PendingMovesStorage.load(file, loadedLobby, loadedHardcore);
        
        assertEquals(3, loadedLobby.size());
        assertEquals(2, loadedHardcore.size());
        assertTrue(loadedLobby.contains(l1));
        assertTrue(loadedLobby.contains(l2));
        assertTrue(loadedLobby.contains(l3));
        assertTrue(loadedHardcore.contains(h1));
        assertTrue(loadedHardcore.contains(h2));
    }

    @Test
    void testLoadClearsExistingSets(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.json").toFile();
        
        Set<UUID> lobbySet = new java.util.HashSet<>();
        Set<UUID> hardcoreSet = new java.util.HashSet<>();
        
        UUID savedUUID = UUID.randomUUID();
        lobbySet.add(savedUUID);
        
        PendingMovesStorage.save(file, lobbySet, hardcoreSet);
        
        // Add different UUIDs before loading
        lobbySet.add(UUID.randomUUID());
        lobbySet.add(UUID.randomUUID());
        hardcoreSet.add(UUID.randomUUID());
        
        PendingMovesStorage.load(file, lobbySet, hardcoreSet);
        
        // Sets should be cleared and only contain loaded data
        assertEquals(1, lobbySet.size());
        assertTrue(lobbySet.contains(savedUUID));
        assertEquals(0, hardcoreSet.size());
    }
}
