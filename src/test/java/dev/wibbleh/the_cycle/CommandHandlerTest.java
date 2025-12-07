package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CommandHandlerTest {

    @Mock
    private Main mockPlugin;

    @Mock
    private CommandSender mockSender;

    @Mock
    private Command mockCommand;

    private CommandHandler handler;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        handler = new CommandHandler(mockPlugin);
        sentMessages = new ArrayList<>();
        lenient().doAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return null;
        }).when(mockSender).sendMessage(anyString());
        // By default treat the mocked plugin as the hardcore backend to match typical tests
        lenient().when(mockPlugin.isHardcoreBackend()).thenReturn(true);
        // By default grant the mock sender the command permission so tests that expect
        // execution/forwarding are not blocked by the new permission check.
        lenient().when(mockSender.hasPermission("thecycle.cycle")).thenReturn(true);
    }

    @Test
    void testHandleNoArgs() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{});
        
        assertTrue(result);
        assertEquals(1, sentMessages.size());
        assertTrue(sentMessages.get(0).contains("Usage"));
    }

    @Test
    void testHandleSetCycleValidNumber() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"setcycle", "5"});
        
        assertTrue(result);
        verify(mockPlugin).setCycleNumber(5);
        assertEquals(1, sentMessages.size());
        assertEquals("Cycle number set to 5", sentMessages.get(0));
    }

    @Test
    void testHandleSetCycleInvalidNumber() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"setcycle", "invalid"});
        
        assertTrue(result);
        verify(mockPlugin, never()).setCycleNumber(anyInt());
        assertEquals(1, sentMessages.size());
        assertEquals("Invalid number.", sentMessages.get(0));
    }

    @Test
    void testHandleSetCycleMissingNumber() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"setcycle"});
        
        assertFalse(result);
        verify(mockPlugin, never()).setCycleNumber(anyInt());
    }

    @Test
    void testHandleCycleNow() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"cycle-now"});
        
        assertTrue(result);
        verify(mockPlugin).triggerCycle();
        assertEquals(1, sentMessages.size());
        assertEquals("Cycling world now (executed on this hardcore backend).", sentMessages.get(0));
    }

    @Test
    void testHandleCycleNowForwarded() {
        when(mockCommand.getName()).thenReturn("cycle");
        // Simulate that this is a lobby instance
        lenient().when(mockPlugin.isHardcoreBackend()).thenReturn(false);
        lenient().when(mockPlugin.sendRpcToHardcore("cycle-now", mockSender)).thenReturn(true);

        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"cycle-now"});

        assertTrue(result);
        verify(mockPlugin, never()).triggerCycle();
        assertEquals(1, sentMessages.size());
        assertEquals("Cycle request forwarded to hardcore backend.", sentMessages.get(0));
    }

    @Test
    void testHandleStatus() {
        when(mockCommand.getName()).thenReturn("cycle");
        when(mockPlugin.getCycleNumber()).thenReturn(3);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(new ArrayList<>());
            
            boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"status"});
            
            assertTrue(result);
            assertEquals(1, sentMessages.size());
            assertTrue(sentMessages.get(0).contains("Cycle=3"));
            assertTrue(sentMessages.get(0).contains("playersOnline=0"));
        }
    }

    @Test
    void testHandleUnknownCommand() {
        when(mockCommand.getName()).thenReturn("cycle");
        
        boolean result = handler.handle(mockSender, mockCommand, "cycle", new String[]{"unknown"});
        
        assertFalse(result);
    }

    @Test
    void testHandleNonCycleCommand() {
        when(mockCommand.getName()).thenReturn("other");
        
        boolean result = handler.handle(mockSender, mockCommand, "other", new String[]{"arg"});
        
        assertFalse(result);
    }

    @Test
    void testHandleSetCycleCaseInsensitive() {
        when(mockCommand.getName()).thenReturn("CYCLE");
        
        boolean result = handler.handle(mockSender, mockCommand, "CYCLE", new String[]{"SETCYCLE", "10"});
        
        assertTrue(result);
        verify(mockPlugin).setCycleNumber(10);
    }

    @Test
    void testHandleStatusCaseInsensitive() {
        when(mockCommand.getName()).thenReturn("Cycle");
        when(mockPlugin.getCycleNumber()).thenReturn(7);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(new ArrayList<>());
            
            boolean result = handler.handle(mockSender, mockCommand, "Cycle", new String[]{"STATUS"});
            
            assertTrue(result);
            assertTrue(sentMessages.get(0).contains("Cycle=7"));
        }
    }
}
