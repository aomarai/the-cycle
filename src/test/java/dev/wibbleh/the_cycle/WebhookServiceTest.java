package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private JavaPlugin mockPlugin;

    @Mock
    private BukkitScheduler mockScheduler;

    @BeforeEach
    void setUp() {
        // Mock the logger to avoid NPE - using lenient to avoid unnecessary stubbing warnings
        lenient().when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
    }

    @Test
    void testSendWithEmptyUrl() {
        WebhookService service = new WebhookService(mockPlugin, "");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.send("{\"test\": \"payload\"}");
            
            // Should not schedule any async task when URL is empty
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testSendWithNullUrl() {
        WebhookService service = new WebhookService(mockPlugin, null);
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.send("{\"test\": \"payload\"}");
            
            // Should not schedule any async task when URL is null
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testSendWithWhitespaceUrl() {
        WebhookService service = new WebhookService(mockPlugin, "   ");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.send("{\"test\": \"payload\"}");
            
            // Should not schedule any async task when URL is whitespace
            verify(mockScheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        }
    }

    @Test
    void testSendSchedulesAsyncTask() {
        WebhookService service = new WebhookService(mockPlugin, "https://example.com/webhook");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.send("{\"test\": \"payload\"}");
            
            // Should schedule an async task when URL is valid
            verify(mockScheduler, times(1)).runTaskAsynchronously(eq(mockPlugin), any(Runnable.class));
        }
    }

    @Test
    void testSendWithNullPayload() {
        WebhookService service = new WebhookService(mockPlugin, "https://example.com/webhook");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            // Should handle null payload without crashing
            service.send(null);
            
            verify(mockScheduler, times(1)).runTaskAsynchronously(eq(mockPlugin), any(Runnable.class));
        }
    }

    @Test
    void testSendWithEmptyPayload() {
        WebhookService service = new WebhookService(mockPlugin, "https://example.com/webhook");
        
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            
            service.send("");
            
            verify(mockScheduler, times(1)).runTaskAsynchronously(eq(mockPlugin), any(Runnable.class));
        }
    }
}
