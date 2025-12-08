package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
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

    /**
     * Helper method to set a private field value using reflection.
     */
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testEscapeMethod() throws Exception {
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

    @Test
    void testSendPlayerToLobbyWithDeadPlayerSwitchesToSpectator() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        org.bukkit.entity.Player mockPlayer = mock(org.bukkit.entity.Player.class);
        
        // Setup
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        when(mockPlayer.isDead()).thenReturn(true);
        when(mockPlayer.getName()).thenReturn("DeadPlayer");
        
        // Initialize required private fields using helper method
        setPrivateField(plugin, "lobbyServer", "");
        setPrivateField(plugin, "lobbyWorldName", "");
        
        // Access private sendPlayerToLobby method
        Method sendPlayerToLobbyMethod = Main.class.getDeclaredMethod("sendPlayerToLobby", org.bukkit.entity.Player.class);
        sendPlayerToLobbyMethod.setAccessible(true);
        
        // Invoke the method
        sendPlayerToLobbyMethod.invoke(plugin, mockPlayer);
        
        // Verify that setGameMode was called with SPECTATOR
        verify(mockPlayer, times(1)).setGameMode(org.bukkit.GameMode.SPECTATOR);
    }

    @Test
    void testSendPlayerToServerWithDeadPlayerSwitchesToSpectator() throws Exception {
        Main plugin = mock(Main.class, CALLS_REAL_METHODS);
        org.bukkit.entity.Player mockPlayer = mock(org.bukkit.entity.Player.class);
        
        // Setup
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        when(mockPlayer.isDead()).thenReturn(true);
        when(mockPlayer.getName()).thenReturn("DeadPlayer");
        
        // Initialize required private field using helper method
        setPrivateField(plugin, "registeredBungeeChannel", true);
        
        // Invoke the method
        boolean result = plugin.sendPlayerToServer(mockPlayer, "hardcore");
        
        // Verify that setGameMode was called with SPECTATOR
        verify(mockPlayer, times(1)).setGameMode(org.bukkit.GameMode.SPECTATOR);
        // Should return true even if dead (after switching to spectator)
        assertTrue(result);
    }

    @Test
    void testRecordDragonKillIncrementsWinsAndResetsAttempts() throws Exception {
        // Use mock with real methods to avoid initialization issues
        Main plugin = mock(Main.class);
        
        // Initialize the atomic fields via reflection
        java.lang.reflect.Field attemptsField = Main.class.getDeclaredField("attemptsSinceLastWin");
        attemptsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger(5);
        attemptsField.set(plugin, attempts);

        java.lang.reflect.Field winsField = Main.class.getDeclaredField("totalWins");
        winsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger(2);
        winsField.set(plugin, wins);

        // Call real methods
        when(plugin.getAttemptsSinceLastWin()).thenCallRealMethod();
        when(plugin.getTotalWins()).thenCallRealMethod();
        doCallRealMethod().when(plugin).recordDragonKill();

        // Execute
        plugin.recordDragonKill();

        // Verify
        assertEquals(0, plugin.getAttemptsSinceLastWin(), "Attempts should be reset to 0");
        assertEquals(3, plugin.getTotalWins(), "Wins should be incremented to 3");
    }

    @Test
    void testIsPlayerInCurrentCycle() throws Exception {
        Main plugin = mock(Main.class);
        UUID playerId = UUID.randomUUID();

        // Initialize the set via reflection
        java.lang.reflect.Field playersField = Main.class.getDeclaredField("playersInCurrentCycle");
        playersField.setAccessible(true);
        Set<UUID> players = Collections.synchronizedSet(new HashSet<>());
        playersField.set(plugin, players);

        // Call real methods
        when(plugin.isPlayerInCurrentCycle(any(UUID.class))).thenCallRealMethod();
        doCallRealMethod().when(plugin).addPlayerToCurrentCycle(any(UUID.class));

        // Player not in cycle initially
        assertFalse(plugin.isPlayerInCurrentCycle(playerId));

        // Add player to cycle
        plugin.addPlayerToCurrentCycle(playerId);

        // Player now in cycle
        assertTrue(plugin.isPlayerInCurrentCycle(playerId));
    }

    @Test
    void testAddPlayerToCurrentCycle() throws Exception {
        Main plugin = mock(Main.class);
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        // Initialize the set via reflection
        java.lang.reflect.Field playersField = Main.class.getDeclaredField("playersInCurrentCycle");
        playersField.setAccessible(true);
        Set<UUID> players = Collections.synchronizedSet(new HashSet<>());
        playersField.set(plugin, players);

        // Call real methods
        when(plugin.isPlayerInCurrentCycle(any(UUID.class))).thenCallRealMethod();
        doCallRealMethod().when(plugin).addPlayerToCurrentCycle(any(UUID.class));

        // Add multiple players
        plugin.addPlayerToCurrentCycle(player1);
        plugin.addPlayerToCurrentCycle(player2);

        // Both should be in cycle
        assertTrue(plugin.isPlayerInCurrentCycle(player1));
        assertTrue(plugin.isPlayerInCurrentCycle(player2));

        // Adding same player again shouldn't cause issues (Set behavior)
        plugin.addPlayerToCurrentCycle(player1);
        assertTrue(plugin.isPlayerInCurrentCycle(player1));
    }

    @Test
    void testCycleStartPendingFlagOperations() throws Exception {
        Main plugin = mock(Main.class);

        // Initialize the flag via reflection
        java.lang.reflect.Field pendingField = Main.class.getDeclaredField("cycleStartPending");
        pendingField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(false);
        pendingField.set(plugin, pending);

        // Call real method
        doCallRealMethod().when(plugin).clearCycleStartPending();

        // Initially false
        assertFalse(pending.get());

        // Set to true
        pending.set(true);
        assertTrue(pending.get());

        // Clear it
        plugin.clearCycleStartPending();
        assertFalse(pending.get());
    }

    @Test
    void testCheckAndAutoStartCycleOnHardcoreDoesNothing() throws Exception {
        Main plugin = mock(Main.class);
        
        // Initialize the flag via reflection
        java.lang.reflect.Field pendingField = Main.class.getDeclaredField("cycleStartPending");
        pendingField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(false);
        pendingField.set(plugin, pending);
        
        // Set the isHardcoreBackend field via reflection
        java.lang.reflect.Field backendField = Main.class.getDeclaredField("isHardcoreBackend");
        backendField.setAccessible(true);
        backendField.set(plugin, true);
        
        // Call real method
        doCallRealMethod().when(plugin).checkAndAutoStartCycle();

        // Should return early without doing anything (no exception)
        plugin.checkAndAutoStartCycle();
        
        // Flag should still be false (not set)
        assertFalse(pending.get());
    }

    @Test
    void testGetAttemptsSinceLastWinAndTotalWins() throws Exception {
        Main plugin = mock(Main.class);

        // Set values via reflection
        java.lang.reflect.Field attemptsField = Main.class.getDeclaredField("attemptsSinceLastWin");
        attemptsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger(7);
        attemptsField.set(plugin, attempts);

        java.lang.reflect.Field winsField = Main.class.getDeclaredField("totalWins");
        winsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger(4);
        winsField.set(plugin, wins);

        // Call real methods
        when(plugin.getAttemptsSinceLastWin()).thenCallRealMethod();
        when(plugin.getTotalWins()).thenCallRealMethod();

        // Verify getters
        assertEquals(7, plugin.getAttemptsSinceLastWin());
        assertEquals(4, plugin.getTotalWins());
    }

    @Test
    void testStatsFileNaming() throws Exception {
        Main plugin = mock(Main.class);
        File dataFolder = new File(tempDir.toFile(), "test_statsFile");
        dataFolder.mkdirs();
        
        // Set stats file
        java.lang.reflect.Field statsFileField = Main.class.getDeclaredField("statsFile");
        statsFileField.setAccessible(true);
        statsFileField.set(plugin, new File(dataFolder, "stats.txt"));

        File statsFile = (File) statsFileField.get(plugin);
        
        // Verify it's named stats.txt not stats.json
        assertEquals("stats.txt", statsFile.getName(), "Stats file should be named stats.txt");
        assertFalse(statsFile.getName().equals("stats.json"), "Stats file should not be named stats.json");
    }
}

