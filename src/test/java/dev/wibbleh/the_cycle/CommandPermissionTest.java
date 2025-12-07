package dev.wibbleh.the_cycle;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class CommandPermissionTest {

    @Mock
    private Main mockMain;

    @Mock
    private CommandSender mockSender;

    @Mock
    private Command mockCommand;

    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CommandHandler(mockMain);
        // default: allow getName
        when(mockCommand.getName()).thenReturn("cycle");
    }

    @Test
    void testCycleNowDeniedWhenNoPermission() {
        when(mockSender.hasPermission("thecycle.cycle")).thenReturn(false);

        boolean res = handler.handle(mockSender, mockCommand, "cycle", new String[]{"cycle-now"});
        // result should be true (handled)
        assertTrue(res);

        // command handled and permission message sent
        verify(mockSender).sendMessage("You do not have permission to use that command.");
        // Ensure no forwarding or triggering occurred
        verify(mockMain, never()).sendRpcToHardcore(anyString(), any());
        verify(mockMain, never()).triggerCycle();
    }

    @Test
    void testCycleNowForwardedWhenAllowedOnLobby() {
        when(mockSender.hasPermission("thecycle.cycle")).thenReturn(true);
        when(mockMain.isHardcoreBackend()).thenReturn(false);
        when(mockMain.sendRpcToHardcore("cycle-now", mockSender)).thenReturn(true);

        boolean res = handler.handle(mockSender, mockCommand, "cycle", new String[]{"cycle-now"});
        assertTrue(res);
        verify(mockMain).sendRpcToHardcore("cycle-now", mockSender);
        verify(mockSender).sendMessage("Cycle request forwarded to hardcore backend.");
        verify(mockMain, never()).triggerCycle();
    }

    @Test
    void testCycleNowExecutedWhenAllowedOnHardcore() {
        when(mockSender.hasPermission("thecycle.cycle")).thenReturn(true);
        when(mockMain.isHardcoreBackend()).thenReturn(true);

        boolean res = handler.handle(mockSender, mockCommand, "cycle", new String[]{"cycle-now"});
        assertTrue(res);
        verify(mockMain).triggerCycle();
        verify(mockSender).sendMessage("Cycling world now (executed on this hardcore backend).");
        verify(mockMain, never()).sendRpcToHardcore(anyString(), any());
    }
}
