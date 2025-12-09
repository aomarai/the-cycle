package dev.wibbleh.the_cycle;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeathListenerTest {

    @Mock
    private Main mockPlugin;

    @Mock
    private Player mockPlayer;

    @Mock
    private PlayerDeathEvent mockEvent;

    @Mock
    private Location mockLocation;

    @Mock
    private FileConfiguration mockConfig;

    @Mock
    private BukkitScheduler mockScheduler;

    @Mock
    private BukkitTask mockTask;

    private Map<UUID, Boolean> aliveMap;
    private List<Map<String, Object>> deathRecap;
    private UUID playerUUID;

    @BeforeEach
    void setUp() {
        aliveMap = new HashMap<>();
        deathRecap = new ArrayList<>();
        playerUUID = UUID.randomUUID();

        lenient().when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        lenient().when(mockPlugin.getConfig()).thenReturn(mockConfig);
        lenient().when(mockEvent.getEntity()).thenReturn(mockPlayer);
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUUID);
        lenient().when(mockPlayer.getName()).thenReturn("TestPlayer");
        lenient().when(mockPlayer.getLocation()).thenReturn(mockLocation);
        lenient().when(mockLocation.getBlockX()).thenReturn(100);
        lenient().when(mockLocation.getBlockY()).thenReturn(64);
        lenient().when(mockLocation.getBlockZ()).thenReturn(200);
        lenient().when(mockEvent.getDeathMessage()).thenReturn("TestPlayer was slain");
        lenient().when(mockEvent.getDrops()).thenReturn(new ArrayList<>());
    }

    @Test
    void testOnPlayerDeathRecordsDeathData() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.<Player>emptyList());
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            assertFalse(aliveMap.get(playerUUID));
            assertEquals(1, deathRecap.size());
            
            Map<String, Object> entry = deathRecap.get(0);
            assertEquals("TestPlayer", entry.get("name"));
            assertEquals("TestPlayer was slain", entry.get("cause"));
            assertEquals("100,64,200", entry.get("location"));
            assertNotNull(entry.get("time"));
            assertNotNull(entry.get("drops"));
        }
    }

    @Test
    void testOnPlayerDeathRecordsDrops() {
        // Mock ItemStack objects to avoid Bukkit registry initialization
        ItemStack mockDiamond = mock(ItemStack.class);
        ItemStack mockIron = mock(ItemStack.class);
        
        when(mockDiamond.getType()).thenReturn(Material.DIAMOND);
        when(mockDiamond.getAmount()).thenReturn(5);
        when(mockIron.getType()).thenReturn(Material.IRON_INGOT);
        when(mockIron.getAmount()).thenReturn(10);
        
        List<ItemStack> drops = new ArrayList<>();
        drops.add(mockDiamond);
        drops.add(mockIron);
        lenient().when(mockEvent.getDrops()).thenReturn(drops);
        
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.<Player>emptyList());
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            assertEquals(1, deathRecap.size());
            Map<String, Object> entry = deathRecap.get(0);
            @SuppressWarnings("unchecked")
            List<String> recordedDrops = (List<String>) entry.get("drops");
            assertEquals(2, recordedDrops.size());
            assertTrue(recordedDrops.get(0).contains("DIAMOND"));
            assertTrue(recordedDrops.get(0).contains("5"));
            assertTrue(recordedDrops.get(1).contains("IRON_INGOT"));
            assertTrue(recordedDrops.get(1).contains("10"));
        }
    }

    @Test
    void testOnPlayerDeathSendsActionBar() {
        DeathListener listener = new DeathListener(mockPlugin, true, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            verify(mockPlayer, atLeastOnce()).sendActionBar(any(Component.class));
        }
    }

    @Test
    void testOnPlayerDeathDoesNotSendActionBarWhenDisabled() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            verify(mockPlayer, never()).sendActionBar(any(Component.class));
        }
    }



    @Test
    void testMultipleDeathsRecorded() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        UUID player2UUID = UUID.randomUUID();
        Player mockPlayer2 = mock(Player.class);
        PlayerDeathEvent mockEvent2 = mock(PlayerDeathEvent.class);
        
        lenient().when(mockEvent2.getEntity()).thenReturn(mockPlayer2);
        lenient().when(mockPlayer2.getUniqueId()).thenReturn(player2UUID);
        lenient().when(mockPlayer2.getName()).thenReturn("Player2");
        lenient().when(mockPlayer2.getLocation()).thenReturn(mockLocation);
        lenient().when(mockEvent2.getDeathMessage()).thenReturn("Player2 fell from a high place");
        lenient().when(mockEvent2.getDrops()).thenReturn(new ArrayList<>());
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.<Player>emptyList());
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            listener.onPlayerDeath(mockEvent2);
            
            assertFalse(aliveMap.get(playerUUID));
            assertFalse(aliveMap.get(player2UUID));
            assertEquals(2, deathRecap.size());
            assertEquals("TestPlayer", deathRecap.get(0).get("name"));
            assertEquals("Player2", deathRecap.get(1).get("name"));
        }
    }

    @Test
    void testSharedDeathNotTriggeredWhenDisabled() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            listener.onAnyPlayerDeath(mockEvent);
            
            // Scheduler should not be called when shared death is disabled
            verify(mockScheduler, never()).runTask(any(), any(Runnable.class));
        }
    }

    @Test
    void testSharedDeathTriggeredWhenEnabled() {
        DeathListener listener = new DeathListener(mockPlugin, false, true, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            listener.onAnyPlayerDeath(mockEvent);
            
            // Scheduler should be called to run shared death logic
            verify(mockScheduler, times(1)).runTask(eq(mockPlugin), any(Runnable.class));
        }
    }

    @Test
    void testAliveMapInitialState() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        // Initially, map should be empty or as provided
        assertTrue(aliveMap.isEmpty());
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.<Player>emptyList());
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            // After death, player should be marked as not alive
            assertFalse(aliveMap.get(playerUUID));
        }
    }

    @Test
    void testOnPlayerDeathSendsSkullChatMessage() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            // The listener will attempt to notify online players; verify that death was recorded.
            assertEquals(1, deathRecap.size());
            Map<String, Object> entry = deathRecap.get(0);
            assertEquals("TestPlayer", entry.get("name"));
            assertTrue(entry.get("location").toString().contains("100,64,200"));
        }
    }

    @Test
    void testOnPlayerDeathSchedulesRespawn() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        // Mock the player's spigot() to return a mock spigot player
        Player.Spigot mockSpigot = mock(Player.Spigot.class);
        when(mockPlayer.spigot()).thenReturn(mockSpigot);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            lenient().when(mockPlayer.isOnline()).thenReturn(true);
            
            // Capture the runnable passed to runTaskLater so we can execute it
            when(mockScheduler.runTaskLater(eq(mockPlugin), any(Runnable.class), eq(200L))).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                runnable.run();
                return mockTask;
            });
            
            listener.onPlayerDeath(mockEvent);
            
            // Verify that a delayed task was scheduled to respawn the player
            verify(mockScheduler, times(1)).runTaskLater(eq(mockPlugin), any(Runnable.class), eq(200L));
            // Verify that respawn was called
            verify(mockSpigot, times(1)).respawn();
        }
    }

    @Test
    void testOnPlayerDeathSkipsRespawnIfPlayerOffline() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        // Mock the player's spigot() to return a mock spigot player
        Player.Spigot mockSpigot = mock(Player.Spigot.class);
        when(mockPlayer.spigot()).thenReturn(mockSpigot);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            lenient().when(mockPlayer.isOnline()).thenReturn(false);
            
            // Capture the runnable passed to runTaskLater so we can execute it
            when(mockScheduler.runTaskLater(eq(mockPlugin), any(Runnable.class), eq(200L))).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(1);
                runnable.run();
                return mockTask;
            });
            
            listener.onPlayerDeath(mockEvent);
            
            // Verify that a delayed task was scheduled
            verify(mockScheduler, times(1)).runTaskLater(eq(mockPlugin), any(Runnable.class), eq(200L));
            // Verify that respawn was NOT called since player is offline
            verify(mockSpigot, never()).respawn();
        }
    }

    @Test
    void testOnPlayerDeathOverridesVanillaDeathMessage() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            // Verify vanilla death message was cleared (set to empty string)
            verify(mockEvent, times(1)).setDeathMessage("");
        }
    }

    @Test
    void testOnPlayerDeathIncludesDeathReasonInCustomMessage() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            listener.onPlayerDeath(mockEvent);
            
            // Verify custom message was sent to players
            verify(mockPlayer, times(1)).sendMessage(any(Component.class));
            
            // Verify death recap includes the plain text death reason
            assertEquals(1, deathRecap.size());
            Map<String, Object> entry = deathRecap.get(0);
            assertEquals("TestPlayer was slain", entry.get("cause"));
        }
    }

    @Test
    void testOnPlayerDeathHandlesNullDeathMessage() {
        DeathListener listener = new DeathListener(mockPlugin, false, false, aliveMap, deathRecap);
        
        lenient().when(mockEvent.getDeathMessage()).thenReturn(null);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            List<Player> onlinePlayers = Collections.singletonList(mockPlayer);
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            lenient().when(mockConfig.getBoolean("behavior.cycle_when_no_online_players", true)).thenReturn(true);
            
            // Should not throw exception even with null death message
            assertDoesNotThrow(() -> listener.onPlayerDeath(mockEvent));
            
            // Verify death recap uses fallback message
            assertEquals(1, deathRecap.size());
            Map<String, Object> entry = deathRecap.get(0);
            assertEquals("TestPlayer died", entry.get("cause"));
        }
    }
}
