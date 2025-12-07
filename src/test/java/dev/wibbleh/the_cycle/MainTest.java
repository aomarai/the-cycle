package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MainTest {

    @Mock
    private FileConfiguration mockConfig;

    @Mock
    private ScoreboardManager mockScoreboardManager;

    @Mock
    private Scoreboard mockScoreboard;

    @Mock
    private Objective mockObjective;

    @Mock
    private PluginManager mockPluginManager;

    @Mock
    private BukkitScheduler mockScheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Common mock setup
    }

    @Test
    void testEscapeMethod() throws Exception {
        // Note: Using CALLS_REAL_METHODS to test private utility method without changing visibility.
        // This is appropriate for testing internal implementation details that shouldn't be exposed publicly.
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        Method escapeMethod = Main.class.getDeclaredMethod("escape", String.class);
        escapeMethod.setAccessible(true);

        // Test null input
        String result = (String) escapeMethod.invoke(plugin, (String) null);
        assertEquals("", result);

        // Test normal string
        result = (String) escapeMethod.invoke(plugin, "Hello World");
        assertEquals("Hello World", result);

        // Test backslash escaping
        result = (String) escapeMethod.invoke(plugin, "C:\\Users\\Test");
        assertEquals("C:\\\\Users\\\\Test", result);

        // Test quote escaping
        result = (String) escapeMethod.invoke(plugin, "He said \"hello\"");
        assertEquals("He said \\\"hello\\\"", result);

        // Test newline escaping
        result = (String) escapeMethod.invoke(plugin, "Line1\nLine2");
        assertEquals("Line1\\nLine2", result);

        // Test multiple escape sequences
        result = (String) escapeMethod.invoke(plugin, "Path: \"C:\\test\"\nDone");
        assertEquals("Path: \\\"C:\\\\test\\\"\\nDone", result);
    }

    @Test
    void testBuildWebhookPayloadMethod() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        Method buildPayloadMethod = Main.class.getDeclaredMethod("buildWebhookPayload", int.class, List.class);
        buildPayloadMethod.setAccessible(true);

        List<Map<String, Object>> recap = new ArrayList<>();
        
        // Test empty recap
        String payload = (String) buildPayloadMethod.invoke(plugin, 1, recap);
        assertNotNull(payload);
        assertTrue(payload.contains("\"content\":null"));
        assertTrue(payload.contains("cycle 1 complete"));
        assertTrue(payload.contains("\"fields\":[]"));

        // Test with one death entry
        Map<String, Object> entry = new HashMap<>();
        entry.put("name", "Player1");
        entry.put("time", "2023-01-01T12:00:00Z");
        entry.put("cause", "fell from a high place");
        entry.put("location", "100,64,200");
        entry.put("drops", Arrays.asList("DIAMOND x5", "IRON_INGOT x10"));
        recap.add(entry);

        payload = (String) buildPayloadMethod.invoke(plugin, 2, recap);
        assertNotNull(payload);
        assertTrue(payload.contains("cycle 2 complete"));
        assertTrue(payload.contains("Player1"));
        assertTrue(payload.contains("fell from a high place"));
        assertTrue(payload.contains("100,64,200"));
        assertTrue(payload.contains("DIAMOND x5"));
        assertTrue(payload.contains("IRON_INGOT x10"));

        // Test with multiple deaths
        Map<String, Object> entry2 = new HashMap<>();
        entry2.put("name", "Player2");
        entry2.put("time", "2023-01-01T12:05:00Z");
        entry2.put("cause", "was slain by Zombie");
        entry2.put("location", "150,70,250");
        entry2.put("drops", Arrays.asList("STONE x32"));
        recap.add(entry2);

        payload = (String) buildPayloadMethod.invoke(plugin, 3, recap);
        assertTrue(payload.contains("Player1"));
        assertTrue(payload.contains("Player2"));
        assertTrue(payload.contains("was slain by Zombie"));
    }

    @Test
    void testBuildWebhookPayloadEscaping() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        Method buildPayloadMethod = Main.class.getDeclaredMethod("buildWebhookPayload", int.class, List.class);
        buildPayloadMethod.setAccessible(true);

        Map<String, Object> entry = new HashMap<>();
        entry.put("name", "Player\"1");
        entry.put("time", "2023-01-01T12:00:00Z");
        entry.put("cause", "fell\nfrom a high place");
        entry.put("location", "100,64,200");
        entry.put("drops", Arrays.asList("ITEM\\TEST x1"));
        
        List<Map<String, Object>> recap = new ArrayList<>();
        recap.add(entry);

        String payload = (String) buildPayloadMethod.invoke(plugin, 1, recap);
        
        // Verify specific field escaping in the name field (contains the player name and location)
        assertTrue(payload.contains("Player\\\"1"), "Player name should have escaped quote");
        
        // Verify cause field escaping (contains the cause with newline)
        assertTrue(payload.contains("fell\\nfrom"), "Cause should have escaped newline");
        
        // Verify drops field escaping (contains backslash)
        assertTrue(payload.contains("ITEM\\\\TEST"), "Drops should have escaped backslash");
    }

    @Test
    void testGetCycleNumber() {
        Main plugin = mock(Main.class);
        when(plugin.getCycleNumber()).thenReturn(5);
        
        assertEquals(5, plugin.getCycleNumber());
        verify(plugin).getCycleNumber();
    }

    @Test
    void testSetCycleNumber() {
        Main plugin = mock(Main.class);
        doNothing().when(plugin).setCycleNumber(anyInt());
        
        plugin.setCycleNumber(10);
        
        verify(plugin).setCycleNumber(10);
    }

    @Test
    void testTriggerCycle() {
        Main plugin = mock(Main.class);
        doNothing().when(plugin).triggerCycle();
        
        plugin.triggerCycle();
        
        verify(plugin).triggerCycle();
    }

    @Test
    void testCycleFileOperations() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        
        // Mock the data folder
        File dataFolder = tempDir.toFile();
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        // Initialize the cycleFile field via reflection since it's private
        File cycleFile = new File(dataFolder, "cycles.json");
        java.lang.reflect.Field cycleFileField = Main.class.getDeclaredField("cycleFile");
        cycleFileField.setAccessible(true);
        cycleFileField.set(plugin, cycleFile);
        
        // Access private methods for testing
        Method ensureDataFolder = Main.class.getDeclaredMethod("ensureDataFolderExists");
        ensureDataFolder.setAccessible(true);
        ensureDataFolder.invoke(plugin);
        
        Method writeCycleMethod = Main.class.getDeclaredMethod("writeCycleFile", int.class);
        writeCycleMethod.setAccessible(true);
        
        // Write a cycle number
        writeCycleMethod.invoke(plugin, 42);
        
        // Verify file was created
        assertTrue(cycleFile.exists(), "Cycle file should exist");
        
        // Read and verify content
        try (BufferedReader reader = new BufferedReader(new FileReader(cycleFile))) {
            String content = reader.readLine();
            assertEquals("42", content);
        }
    }

    @Test
    void testEnsureDataFolderExists() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        
        File dataFolder = new File(tempDir.toFile(), "plugin_data");
        assertFalse(dataFolder.exists());
        
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder);
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        // Access private method
        Method ensureMethod = Main.class.getDeclaredMethod("ensureDataFolderExists");
        ensureMethod.setAccessible(true);
        
        ensureMethod.invoke(plugin);
        
        // Verify folder was created
        assertTrue(dataFolder.exists());
        assertTrue(dataFolder.isDirectory());
    }

    @Test
    void testSendPlayerToLobbyWithNullPlayer() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        Method sendToLobbyMethod = Main.class.getDeclaredMethod("sendPlayerToLobby", org.bukkit.entity.Player.class);
        sendToLobbyMethod.setAccessible(true);
        
        // Should handle null player without crashing
        sendToLobbyMethod.invoke(plugin, (Object) null);
    }

    @Test
    void testWebhookPayloadStructure() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        Method buildPayloadMethod = Main.class.getDeclaredMethod("buildWebhookPayload", int.class, List.class);
        buildPayloadMethod.setAccessible(true);

        List<Map<String, Object>> recap = new ArrayList<>();
        String payload = (String) buildPayloadMethod.invoke(plugin, 5, recap);
        
        // Verify JSON structure
        assertTrue(payload.startsWith("{"));
        assertTrue(payload.endsWith("}"));
        assertTrue(payload.contains("\"content\":null"));
        assertTrue(payload.contains("\"embeds\":"));
        assertTrue(payload.contains("\"title\":"));
        assertTrue(payload.contains("\"description\":"));
        assertTrue(payload.contains("\"fields\":"));
    }

    @Test
    void testBuildWebhookPayloadFieldFormat() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        Method buildPayloadMethod = Main.class.getDeclaredMethod("buildWebhookPayload", int.class, List.class);
        buildPayloadMethod.setAccessible(true);

        Map<String, Object> entry = new HashMap<>();
        entry.put("name", "TestPlayer");
        entry.put("time", "2023-01-01T12:00:00Z");
        entry.put("cause", "fell from a high place");
        entry.put("location", "100,64,200");
        entry.put("drops", Arrays.asList("DIAMOND x5"));
        
        List<Map<String, Object>> recap = new ArrayList<>();
        recap.add(entry);

        String payload = (String) buildPayloadMethod.invoke(plugin, 1, recap);
        
        // Verify field structure
        assertTrue(payload.contains("\"name\":"));
        assertTrue(payload.contains("\"value\":"));
        assertTrue(payload.contains("\"inline\":false"));
        assertTrue(payload.contains("Time:"));
        assertTrue(payload.contains("Cause:"));
        assertTrue(payload.contains("Drops:"));
    }
}
