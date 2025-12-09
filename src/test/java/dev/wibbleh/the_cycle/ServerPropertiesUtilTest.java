package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServerPropertiesUtilTest {

    @Test
    public void updateLevelName_fileDoesNotExist_createsFileWithProperty(@TempDir Path tempDir) {
        Path propertiesPath = tempDir.resolve("server.properties");
        
        boolean result = ServerPropertiesUtil.updateLevelName(propertiesPath, "hardcore_cycle_1");
        
        assertTrue(result, "Should successfully create file and update property");
        assertTrue(Files.exists(propertiesPath), "File should be created");
        
        String content = readFile(propertiesPath);
        assertTrue(content.contains("level-name=hardcore_cycle_1"), "Should contain the updated property");
    }

    @Test
    public void updateLevelName_fileExists_updatesProperty(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        
        // Create initial file with level-name=world
        Files.writeString(propertiesPath, """
                # Minecraft server properties
                level-name=world
                gamemode=survival
                """, StandardCharsets.UTF_8);
        
        boolean result = ServerPropertiesUtil.updateLevelName(propertiesPath, "hardcore_cycle_2");
        
        assertTrue(result, "Should successfully update property");
        String content = readFile(propertiesPath);
        assertTrue(content.contains("level-name=hardcore_cycle_2"), "Should contain the updated property");
        assertFalse(content.contains("level-name=world"), "Should not contain the old value");
        assertTrue(content.contains("gamemode=survival"), "Should preserve other properties");
    }

    @Test
    public void updateLevelName_preservesComments(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        
        // Create file with comments
        Files.writeString(propertiesPath, """
                # Minecraft server properties
                # This is a comment
                level-name=world
                # Another comment
                gamemode=survival
                """, StandardCharsets.UTF_8);
        
        ServerPropertiesUtil.updateLevelName(propertiesPath, "hardcore_cycle_3");
        
        String content = readFile(propertiesPath);
        assertTrue(content.contains("# Minecraft server properties"), "Should preserve header comment");
        assertTrue(content.contains("# This is a comment"), "Should preserve inline comments");
        assertTrue(content.contains("# Another comment"), "Should preserve other comments");
    }

    @Test
    public void updateLevelName_fileExistsWithoutLevelName_addsProperty(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        
        // Create file without level-name property
        Files.writeString(propertiesPath, """
                # Minecraft server properties
                gamemode=survival
                difficulty=normal
                """, StandardCharsets.UTF_8);
        
        boolean result = ServerPropertiesUtil.updateLevelName(propertiesPath, "hardcore_cycle_4");
        
        assertTrue(result, "Should successfully add property");
        String content = readFile(propertiesPath);
        assertTrue(content.contains("level-name=hardcore_cycle_4"), "Should contain the new property");
        assertTrue(content.contains("gamemode=survival"), "Should preserve existing properties");
    }

    @Test
    public void updateLevelName_createsBackup(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        Path backupPath = tempDir.resolve("server.properties.backup");
        
        // Create initial file
        String originalContent = "level-name=world\ngamemode=survival\n";
        Files.writeString(propertiesPath, originalContent, StandardCharsets.UTF_8);
        
        ServerPropertiesUtil.updateLevelName(propertiesPath, "hardcore_cycle_5");
        
        assertTrue(Files.exists(backupPath), "Backup file should be created");
        String backupContent = readFile(backupPath);
        assertEquals(originalContent, backupContent, "Backup should contain original content");
    }

    @Test
    public void updateLevelName_nullPath_returnsFalse() {
        boolean result = ServerPropertiesUtil.updateLevelName(null, "hardcore_cycle_1");
        assertFalse(result, "Should return false for null path");
    }

    @Test
    public void updateLevelName_nullWorldName_returnsFalse(@TempDir Path tempDir) {
        Path propertiesPath = tempDir.resolve("server.properties");
        boolean result = ServerPropertiesUtil.updateLevelName(propertiesPath, null);
        assertFalse(result, "Should return false for null world name");
    }

    @Test
    public void updateLevelName_emptyWorldName_returnsFalse(@TempDir Path tempDir) {
        Path propertiesPath = tempDir.resolve("server.properties");
        boolean result = ServerPropertiesUtil.updateLevelName(propertiesPath, "  ");
        assertFalse(result, "Should return false for empty world name");
    }

    @Test
    public void getCurrentLevelName_fileExists_returnsValue(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        Files.writeString(propertiesPath, "level-name=my_world\n", StandardCharsets.UTF_8);
        
        String result = ServerPropertiesUtil.getCurrentLevelName(propertiesPath);
        
        assertEquals("my_world", result, "Should return the current level-name value");
    }

    @Test
    public void getCurrentLevelName_fileDoesNotExist_returnsNull(@TempDir Path tempDir) {
        Path propertiesPath = tempDir.resolve("nonexistent.properties");
        
        String result = ServerPropertiesUtil.getCurrentLevelName(propertiesPath);
        
        assertNull(result, "Should return null when file doesn't exist");
    }

    @Test
    public void getCurrentLevelName_propertyNotFound_returnsNull(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        Files.writeString(propertiesPath, "gamemode=survival\n", StandardCharsets.UTF_8);
        
        String result = ServerPropertiesUtil.getCurrentLevelName(propertiesPath);
        
        assertNull(result, "Should return null when property is not found");
    }

    @Test
    public void getCurrentLevelName_nullPath_returnsNull() {
        String result = ServerPropertiesUtil.getCurrentLevelName(null);
        assertNull(result, "Should return null for null path");
    }

    @Test
    public void getCurrentLevelName_ignoresCommentedLine(@TempDir Path tempDir) throws IOException {
        Path propertiesPath = tempDir.resolve("server.properties");
        Files.writeString(propertiesPath, """
                # level-name=commented_world
                level-name=actual_world
                """, StandardCharsets.UTF_8);
        
        String result = ServerPropertiesUtil.getCurrentLevelName(propertiesPath);
        
        assertEquals("actual_world", result, "Should ignore commented line and return actual value");
    }

    // Helper method to read file content
    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("Failed to read file: " + e.getMessage());
            return null;
        }
    }
}
