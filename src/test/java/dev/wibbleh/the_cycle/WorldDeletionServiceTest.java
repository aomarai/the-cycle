package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WorldDeletionServiceTest {

    @Mock
    private JavaPlugin mockPlugin;

    @Mock
    private BukkitScheduler mockScheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        lenient().when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        lenient().when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());
    }

    @Test
    void testScheduleDeleteWithDeletionDisabled() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, false, false, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.scheduleDeleteWorldFolder("test_world");
            
            // Should not schedule any deletion when deletion is disabled
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testScheduleDeleteWithNullWorldName() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.scheduleDeleteWorldFolder(null);
            
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testScheduleDeleteWithEmptyWorldName() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.scheduleDeleteWorldFolder("   ");
            
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testScheduleDeleteDeferred() throws Exception {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, true, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.scheduleDeleteWorldFolder("test_world");
            
            // Should not schedule async deletion when deferred
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
            
            // Should create pending_deletes.txt file
            File pendingFile = new File(tempDir.toFile(), "pending_deletes.txt");
            assertTrue(pendingFile.exists());
        }
    }

    @Test
    void testScheduleDeleteAsync() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, true);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.scheduleDeleteWorldFolder("test_world");
            
            // Should schedule async deletion
            verify(mockScheduler, times(1)).runTaskAsynchronously(eq(mockPlugin), any(Runnable.class));
        }
    }

    @Test
    void testScheduleDeleteSync() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            mockedBukkit.when(Bukkit::getWorldContainer).thenReturn(tempDir.toFile());
            
            service.scheduleDeleteWorldFolder("test_world");
            
            // Should not schedule async when sync deletion is configured
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testProcessPendingDeletionsWithNoFile() {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            // Should handle missing file gracefully
            service.processPendingDeletions();
            
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testProcessPendingDeletionsWithEmptyFile() throws Exception {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        File pendingFile = new File(tempDir.toFile(), "pending_deletes.txt");
        assertTrue(pendingFile.createNewFile(), "File should not already exist");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.processPendingDeletions();
            
            // Should not schedule any deletions for empty file
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
            // Empty file should be deleted
            assertFalse(pendingFile.exists());
        }
    }

    @Test
    void testProcessPendingDeletionsWithWorldNames() throws Exception {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, false, false);
        
        File pendingFile = new File(tempDir.toFile(), "pending_deletes.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(pendingFile))) {
            writer.write("world1");
            writer.newLine();
            writer.write("world2");
            writer.newLine();
            writer.write("");
            writer.newLine();
            writer.write("world3");
            writer.newLine();
        }
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.processPendingDeletions();
            
            // Should schedule 3 async deletions (ignoring empty line)
            verify(mockScheduler, times(3)).runTaskAsynchronously(eq(mockPlugin), any(Runnable.class));
        }
    }

    @Test
    void testRecordPendingDeleteNoDuplicates() throws Exception {
        WorldDeletionService service = new WorldDeletionService(mockPlugin, true, true, false);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            // Schedule the same world twice
            service.scheduleDeleteWorldFolder("duplicate_world");
            service.scheduleDeleteWorldFolder("duplicate_world");
            
            File pendingFile = new File(tempDir.toFile(), "pending_deletes.txt");
            assertTrue(pendingFile.exists());
            
            // Verify deduplication - should only have one occurrence
            long count = java.nio.file.Files.lines(pendingFile.toPath())
                    .filter(line -> line.trim().equals("duplicate_world"))
                    .count();
            assertEquals(1, count, "Should have exactly one occurrence due to deduplication");
        }
    }
}
