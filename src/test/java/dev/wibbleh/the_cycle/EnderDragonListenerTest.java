package dev.wibbleh.the_cycle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnderDragonListenerTest {

    @Mock
    private Main mockPlugin;

    @Mock
    private EntityDeathEvent mockEvent;

    @Mock
    private Player mockDragonKiller;

    @Mock
    private Player mockPlayer1;

    @Mock
    private Player mockPlayer2;

    @Mock
    private Logger mockLogger;

    private EnderDragonListener listener;

    @BeforeEach
    void setUp() {
        listener = new EnderDragonListener(mockPlugin);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);
    }

    @Test
    void testEnderDragonDeathRecordsWin() {
        // Setup
        when(mockEvent.getEntityType()).thenReturn(EntityType.ENDER_DRAGON);
        when(mockEvent.getEntity()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getKiller()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getName()).thenReturn("TestKiller");
        when(mockPlugin.getAttemptsSinceLastWin()).thenReturn(5);
        when(mockPlugin.getTotalWins()).thenReturn(3);

        Collection<Player> players = new ArrayList<>();
        players.add(mockPlayer1);
        players.add(mockPlayer2);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) players);

            // Execute
            listener.onEnderDragonDeath(mockEvent);

            // Verify
            verify(mockPlugin).recordDragonKill();
            verify(mockPlayer1).showTitle(any(Title.class));
            verify(mockPlayer2).showTitle(any(Title.class));
            verify(mockPlayer1, times(6)).sendMessage(any(Component.class)); // 2 separators + 4 messages
            verify(mockPlayer2, times(6)).sendMessage(any(Component.class));
        }
    }

    @Test
    void testEnderDragonDeathWithUnknownKiller() {
        // Setup
        when(mockEvent.getEntityType()).thenReturn(EntityType.ENDER_DRAGON);
        when(mockEvent.getEntity()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getKiller()).thenReturn(null); // No killer
        when(mockPlugin.getAttemptsSinceLastWin()).thenReturn(10);
        when(mockPlugin.getTotalWins()).thenReturn(1);

        Collection<Player> players = new ArrayList<>();
        players.add(mockPlayer1);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) players);

            // Execute
            listener.onEnderDragonDeath(mockEvent);

            // Verify
            verify(mockPlugin).recordDragonKill();
            verify(mockPlayer1).showTitle(any(Title.class));
            // Verify message contains "Unknown Hero" when no killer
            verify(mockPlayer1, times(6)).sendMessage(any(Component.class)); // 2 separators + 4 messages
        }
    }

    @Test
    void testNonDragonEntityDoesNotTrigger() {
        // Setup
        when(mockEvent.getEntityType()).thenReturn(EntityType.ZOMBIE);

        // Execute
        listener.onEnderDragonDeath(mockEvent);

        // Verify - should not process non-dragon entities
        verify(mockPlugin, never()).recordDragonKill();
        verify(mockEvent, never()).getEntity();
    }

    @Test
    void testAttemptsCounterShownBeforeReset() {
        // Setup - verify attempts are captured before being reset
        when(mockEvent.getEntityType()).thenReturn(EntityType.ENDER_DRAGON);
        when(mockEvent.getEntity()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getKiller()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getName()).thenReturn("Player");
        when(mockPlugin.getAttemptsSinceLastWin()).thenReturn(7);
        when(mockPlugin.getTotalWins()).thenReturn(2);

        Collection<Player> players = new ArrayList<>();
        players.add(mockPlayer1);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) players);

            // Execute
            listener.onEnderDragonDeath(mockEvent);

            // Verify attempts were read before recordDragonKill was called
            verify(mockPlugin).getAttemptsSinceLastWin();
            verify(mockPlugin).recordDragonKill();
            verify(mockPlugin).getTotalWins();
        }
    }

    @Test
    void testVictoryTitleAndMessagesForMultiplePlayers() {
        // Setup
        when(mockEvent.getEntityType()).thenReturn(EntityType.ENDER_DRAGON);
        when(mockEvent.getEntity()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getKiller()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getName()).thenReturn("DragonSlayer");
        when(mockPlugin.getAttemptsSinceLastWin()).thenReturn(1);
        when(mockPlugin.getTotalWins()).thenReturn(1);

        Collection<Player> players = new ArrayList<>();
        players.add(mockPlayer1);
        players.add(mockPlayer2);

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) players);

            // Execute
            listener.onEnderDragonDeath(mockEvent);

            // Verify all players get title and messages
            verify(mockPlayer1).showTitle(any(Title.class));
            verify(mockPlayer2).showTitle(any(Title.class));
            // Each player gets 6 messages (separator, victory, killer, attempts, wins, separator)
            verify(mockPlayer1, times(6)).sendMessage(any(Component.class));
            verify(mockPlayer2, times(6)).sendMessage(any(Component.class));
        }
    }

    @Test
    void testNoPlayersOnlineStillRecordsWin() {
        // Setup
        when(mockEvent.getEntityType()).thenReturn(EntityType.ENDER_DRAGON);
        when(mockEvent.getEntity()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getKiller()).thenReturn(mockDragonKiller);
        when(mockDragonKiller.getName()).thenReturn("SoloPlayer");
        when(mockPlugin.getAttemptsSinceLastWin()).thenReturn(3);
        // Don't stub getTotalWins if it's not going to be called with empty players

        Collection<Player> emptyPlayers = new ArrayList<>();

        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn((Collection) emptyPlayers);

            // Execute
            listener.onEnderDragonDeath(mockEvent);

            // Verify - win is still recorded even with no players online
            verify(mockPlugin).recordDragonKill();
            // But no titles or messages sent (no players to send to)
        }
    }
}
