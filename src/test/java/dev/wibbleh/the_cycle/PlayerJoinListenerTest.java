package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayerJoinListenerTest {

    @Mock
    private Main mockPlugin;

    @Mock
    private Player mockPlayer;

    @Mock
    private World mockWorld;

    @Mock
    private FileConfiguration mockConfig;

    @Mock
    private BukkitScheduler mockScheduler;

    @Mock
    private BukkitTask mockTask;

    @Mock
    private Logger mockLogger;

    @Mock
    private PlayerJoinEvent mockJoinEvent;

    @Mock
    private PlayerChangedWorldEvent mockChangeWorldEvent;

    private PlayerJoinListener listener;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        listener = new PlayerJoinListener(mockPlugin);
        playerUuid = UUID.randomUUID();
        
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        lenient().when(mockPlayer.getName()).thenReturn("TestPlayer");
        lenient().when(mockPlayer.getWorld()).thenReturn(mockWorld);
        lenient().when(mockJoinEvent.getPlayer()).thenReturn(mockPlayer);
        lenient().when(mockChangeWorldEvent.getPlayer()).thenReturn(mockPlayer);
    }

    @Test
    void testMidCycleJoinOnHardcoreMovesToLobby() {
        // Setup - player joining hardcore during active cycle
        when(mockPlugin.isHardcoreBackend()).thenReturn(true);
        when(mockPlugin.isPlayerInCurrentCycle(playerUuid)).thenReturn(false);
        when(mockWorld.getName()).thenReturn("hardcore_cycle_5");

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            when(mockScheduler.runTask(eq(mockPlugin), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run(); // Execute immediately for testing
                return mockTask;
            });

            // Execute
            listener.onPlayerJoin(mockJoinEvent);

            // Verify
            verify(mockPlayer).sendMessage("§eYou joined during an active cycle. You'll join the next cycle when it starts.");
            verify(mockPlugin).sendPlayerToLobby(mockPlayer);
        }
    }

    @Test
    void testPlayerInCurrentCycleAllowedToStay() {
        // Setup - player is already in current cycle
        when(mockPlugin.isHardcoreBackend()).thenReturn(true);
        when(mockPlugin.isPlayerInCurrentCycle(playerUuid)).thenReturn(true);
        lenient().when(mockWorld.getName()).thenReturn("hardcore_cycle_5");

        // Execute
        listener.onPlayerJoin(mockJoinEvent);

        // Verify - player not moved
        verify(mockPlugin, never()).sendPlayerToLobby(any(Player.class));
    }

    @Test
    void testJoinNonHardcoreWorldOnHardcoreServer() {
        // Setup - player joining non-hardcore world (e.g., lobby world on hardcore server)
        when(mockPlugin.isHardcoreBackend()).thenReturn(true);
        when(mockPlugin.isPlayerInCurrentCycle(playerUuid)).thenReturn(false);
        when(mockWorld.getName()).thenReturn("world");

        // Execute
        listener.onPlayerJoin(mockJoinEvent);

        // Verify - player not moved (not in hardcore world)
        verify(mockPlugin, never()).sendPlayerToLobby(any(Player.class));
    }

    @Test
    void testAutoStartOnLobbyWhenEnabled() {
        // Setup - player joining lobby with auto-start enabled
        when(mockPlugin.isHardcoreBackend()).thenReturn(false);
        when(mockPlugin.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getBoolean("behavior.auto_start_cycles", true)).thenReturn(true);
        when(mockWorld.getName()).thenReturn("lobby");
        when(mockPlayer.isOnline()).thenReturn(true);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockScheduler.runTaskLater(eq(mockPlugin), runnableCaptor.capture(), eq(40L))).thenReturn(mockTask);

            // Execute
            listener.onPlayerJoin(mockJoinEvent);

            // Verify scheduled task was created
            verify(mockScheduler).runTaskLater(eq(mockPlugin), any(Runnable.class), eq(40L));

            // Execute the scheduled task
            Runnable scheduledTask = runnableCaptor.getValue();
            scheduledTask.run();

            // Verify auto-start was checked
            verify(mockPlugin).checkAndAutoStartCycle();
        }
    }

    @Test
    void testAutoStartDisabledInConfig() {
        // Setup - auto-start disabled in config
        when(mockPlugin.isHardcoreBackend()).thenReturn(false);
        when(mockPlugin.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getBoolean("behavior.auto_start_cycles", true)).thenReturn(false);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);

            // Execute
            listener.onPlayerJoin(mockJoinEvent);

            // Verify - no task scheduled when auto-start disabled
            verify(mockScheduler, never()).runTaskLater(any(), any(Runnable.class), anyLong());
            verify(mockPlugin, never()).checkAndAutoStartCycle();
        }
    }

    @Test
    void testAutoStartNotTriggeredWhenPlayerOffline() {
        // Setup - player goes offline before scheduled check
        when(mockPlugin.isHardcoreBackend()).thenReturn(false);
        when(mockPlugin.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getBoolean("behavior.auto_start_cycles", true)).thenReturn(true);
        lenient().when(mockWorld.getName()).thenReturn("lobby");
        when(mockPlayer.isOnline()).thenReturn(false); // Player went offline

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockScheduler.runTaskLater(eq(mockPlugin), runnableCaptor.capture(), eq(40L))).thenReturn(mockTask);

            // Execute
            listener.onPlayerJoin(mockJoinEvent);

            // Execute the scheduled task
            Runnable scheduledTask = runnableCaptor.getValue();
            scheduledTask.run();

            // Verify auto-start not triggered (player offline)
            verify(mockPlugin, never()).checkAndAutoStartCycle();
        }
    }

    @Test
    void testPlayerChangedToHardcoreWorldAddedToCycle() {
        // Setup - player changes to hardcore world
        when(mockWorld.getName()).thenReturn("hardcore_cycle_10");

        // Execute
        listener.onPlayerChangedWorld(mockChangeWorldEvent);

        // Verify - player added to current cycle
        verify(mockPlugin).addPlayerToCurrentCycle(playerUuid);
    }

    @Test
    void testPlayerChangedToNonHardcoreWorldNotAddedToCycle() {
        // Setup - player changes to non-hardcore world
        when(mockWorld.getName()).thenReturn("lobby_world");

        // Execute
        listener.onPlayerChangedWorld(mockChangeWorldEvent);

        // Verify - player not added to cycle
        verify(mockPlugin, never()).addPlayerToCurrentCycle(any(UUID.class));
    }

    @Test
    void testAutoStartOnlyWhenInLobbyWorld() {
        // Setup - player in hardcore world (shouldn't trigger auto-start)
        when(mockPlugin.isHardcoreBackend()).thenReturn(false);
        when(mockPlugin.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getBoolean("behavior.auto_start_cycles", true)).thenReturn(true);
        when(mockWorld.getName()).thenReturn("hardcore_cycle_3");
        when(mockPlayer.isOnline()).thenReturn(true);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
            when(mockScheduler.runTaskLater(eq(mockPlugin), runnableCaptor.capture(), eq(40L))).thenReturn(mockTask);

            // Execute
            listener.onPlayerJoin(mockJoinEvent);

            // Execute the scheduled task
            Runnable scheduledTask = runnableCaptor.getValue();
            scheduledTask.run();

            // Verify - auto-start not triggered (player in hardcore world)
            verify(mockPlugin, never()).checkAndAutoStartCycle();
        }
    }

    @Test
    void testMultiplePlayersJoiningHardcoreDuringCycle() {
        // Setup
        Player player2 = mock(Player.class);
        UUID uuid2 = UUID.randomUUID();
        when(player2.getUniqueId()).thenReturn(uuid2);
        when(player2.getName()).thenReturn("Player2");
        when(player2.getWorld()).thenReturn(mockWorld);

        when(mockPlugin.isHardcoreBackend()).thenReturn(true);
        when(mockPlugin.isPlayerInCurrentCycle(playerUuid)).thenReturn(false);
        when(mockPlugin.isPlayerInCurrentCycle(uuid2)).thenReturn(false);
        when(mockWorld.getName()).thenReturn("hardcore_cycle_1");

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            when(mockScheduler.runTask(eq(mockPlugin), any(Runnable.class))).thenAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return mockTask;
            });

            // Execute - both players join
            listener.onPlayerJoin(mockJoinEvent);
            
            PlayerJoinEvent join2 = mock(PlayerJoinEvent.class);
            when(join2.getPlayer()).thenReturn(player2);
            listener.onPlayerJoin(join2);

            // Verify both moved to lobby
            verify(mockPlugin).sendPlayerToLobby(mockPlayer);
            verify(mockPlugin).sendPlayerToLobby(player2);
        }
    }
}
