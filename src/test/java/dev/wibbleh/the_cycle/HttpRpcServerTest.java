package dev.wibbleh.the_cycle;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpRpcServerTest {

    @Mock
    private Main mockPlugin;

    @Mock
    private FileConfiguration mockConfig;

    @Mock
    private BukkitScheduler mockScheduler;

    @Mock
    private BukkitTask mockTask;

    @Mock
    private HttpExchange mockExchange;

    private Logger testLogger;
    
    // Use a static counter to ensure unique ports across all tests
    private static final AtomicInteger portCounter = new AtomicInteger(10000);

    @BeforeEach
    void setUp() {
        testLogger = Logger.getLogger("HttpRpcServerTest");
        lenient().when(mockPlugin.getLogger()).thenReturn(testLogger);
        lenient().when(mockPlugin.getConfig()).thenReturn(mockConfig);
        lenient().when(mockConfig.getString("server.rpc_secret", "")).thenReturn("test-secret");
    }
    
    private int getNextPort() {
        return portCounter.incrementAndGet();
    }

    @Test
    void shouldRejectRequestsWithNonPostMethod() throws IOException {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            when(mockExchange.getRequestMethod()).thenReturn("GET");

            handler.handle(mockExchange);

            verify(mockExchange).sendResponseHeaders(405, -1);
            verify(mockExchange, never()).getRequestBody();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturn403WhenSignatureIsInvalid() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            String payload = "{\"action\":\"cycle-now\",\"caller\":\"test\"}";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            InputStream is = new ByteArrayInputStream(payloadBytes);

            Headers headers = new Headers();
            headers.add("X-Signature", "invalid-signature");

            when(mockExchange.getRequestMethod()).thenReturn("POST");
            when(mockExchange.getRequestBody()).thenReturn(is);
            when(mockExchange.getRequestHeaders()).thenReturn(headers);

            handler.handle(mockExchange);

            verify(mockExchange).sendResponseHeaders(403, -1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testAcceptsValidSignature() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            String secret = "test-secret";
            String payload = "{\"action\":\"cycle-now\",\"caller\":\"test\"}";
            String validSignature = RpcHttpUtil.computeHmacHex(secret, payload);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            InputStream is = new ByteArrayInputStream(payloadBytes);

            Headers headers = new Headers();
            headers.add("X-Signature", validSignature);

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

            when(mockExchange.getRequestMethod()).thenReturn("POST");
            when(mockExchange.getRequestBody()).thenReturn(is);
            when(mockExchange.getRequestHeaders()).thenReturn(headers);
            when(mockExchange.getResponseBody()).thenReturn(responseBody);
            
            // Mock triggerCycle to not throw exception
            doNothing().when(mockPlugin).triggerCycle();

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                when(mockScheduler.runTask(eq(mockPlugin), any(Runnable.class))).thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    task.run();
                    return mockTask;
                });

                handler.handle(mockExchange);

                verify(mockExchange).sendResponseHeaders(eq(200), eq(2L));
                verify(mockPlugin).triggerCycle();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testWorldReadyAction() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            String secret = "test-secret";
            String payload = "{\"action\":\"world-ready\"}";
            String validSignature = RpcHttpUtil.computeHmacHex(secret, payload);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            InputStream is = new ByteArrayInputStream(payloadBytes);

            Headers headers = new Headers();
            headers.add("X-Signature", validSignature);

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

            when(mockExchange.getRequestMethod()).thenReturn("POST");
            when(mockExchange.getRequestBody()).thenReturn(is);
            when(mockExchange.getRequestHeaders()).thenReturn(headers);
            when(mockExchange.getResponseBody()).thenReturn(responseBody);
            when(mockPlugin.getCountdownSendToHardcoreSeconds()).thenReturn(10);
            
            // Mock scheduleCountdownThenMovePlayersToHardcore
            doNothing().when(mockPlugin).scheduleCountdownThenMovePlayersToHardcore(anyInt());

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                when(mockScheduler.runTask(eq(mockPlugin), any(Runnable.class))).thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    task.run();
                    return mockTask;
                });

                handler.handle(mockExchange);

                verify(mockExchange).sendResponseHeaders(eq(200), eq(2L));
                verify(mockPlugin).scheduleCountdownThenMovePlayersToHardcore(10);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testMovePlayersAction() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            String secret = "test-secret";
            String payload = "{\"action\":\"move-players\"}";
            String validSignature = RpcHttpUtil.computeHmacHex(secret, payload);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            InputStream is = new ByteArrayInputStream(payloadBytes);

            Headers headers = new Headers();
            headers.add("X-Signature", validSignature);

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

            when(mockExchange.getRequestMethod()).thenReturn("POST");
            when(mockExchange.getRequestBody()).thenReturn(is);
            when(mockExchange.getRequestHeaders()).thenReturn(headers);
            when(mockExchange.getResponseBody()).thenReturn(responseBody);
            when(mockPlugin.getHardcoreServerName()).thenReturn("hardcore");

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.Collections.emptyList());
                when(mockScheduler.runTask(eq(mockPlugin), any(Runnable.class))).thenAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    task.run();
                    return mockTask;
                });

                handler.handle(mockExchange);

                verify(mockExchange).sendResponseHeaders(eq(200), eq(2L));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testUnknownAction() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.RpcHandler handler = server.new RpcHandler();

            String secret = "test-secret";
            String payload = "{\"action\":\"unknown-action\"}";
            String validSignature = RpcHttpUtil.computeHmacHex(secret, payload);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            InputStream is = new ByteArrayInputStream(payloadBytes);

            Headers headers = new Headers();
            headers.add("X-Signature", validSignature);

            when(mockExchange.getRequestMethod()).thenReturn("POST");
            when(mockExchange.getRequestBody()).thenReturn(is);
            when(mockExchange.getRequestHeaders()).thenReturn(headers);

            handler.handle(mockExchange);

            verify(mockExchange).sendResponseHeaders(400, -1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testServerStartAndStop() throws IOException {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        
        // Test that start and stop don't throw exceptions
        server.start();
        server.stop(0);
    }

    @Test
    void testConstructorWithNullBindAddress() throws IOException {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, null);
        
        // Should not throw exception with null bind address
        server.start();
        server.stop(0);
    }

    @Test
    void testConstructorWithEmptyBindAddress() throws IOException {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "");
        
        // Should not throw exception with empty bind address
        server.start();
        server.stop(0);
    }

    @Test
    void testHealthEndpointRejectsNonGetRequests() throws IOException {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.HealthHandler handler = server.new HealthHandler();

            when(mockExchange.getRequestMethod()).thenReturn("POST");

            handler.handle(mockExchange);

            verify(mockExchange).sendResponseHeaders(405, -1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testHealthEndpointReturnsValidJson() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.HealthHandler handler = server.new HealthHandler();

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            Headers responseHeaders = new Headers();

            when(mockExchange.getRequestMethod()).thenReturn("GET");
            when(mockExchange.getResponseBody()).thenReturn(responseBody);
            when(mockExchange.getResponseHeaders()).thenReturn(responseHeaders);
            when(mockConfig.getString("server.role", "hardcore")).thenReturn("hardcore");
            when(mockPlugin.getCycleNumber()).thenReturn(5);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.Collections.emptyList());

                handler.handle(mockExchange);

                verify(mockExchange).sendResponseHeaders(eq(200), anyLong());
                String response = responseBody.toString(StandardCharsets.UTF_8);
                assertTrue(response.contains("\"status\":\"ok\""));
                assertTrue(response.contains("\"role\":\"hardcore\""));
                assertTrue(response.contains("\"cycleNumber\":5"));
                assertTrue(response.contains("\"playersOnline\":0"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testHealthEndpointWithLobbyRole() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.HealthHandler handler = server.new HealthHandler();

            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            Headers responseHeaders = new Headers();

            when(mockExchange.getRequestMethod()).thenReturn("GET");
            when(mockExchange.getResponseBody()).thenReturn(responseBody);
            when(mockExchange.getResponseHeaders()).thenReturn(responseHeaders);
            when(mockConfig.getString("server.role", "hardcore")).thenReturn("lobby");
            when(mockPlugin.getCycleNumber()).thenReturn(3);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                // Mock 2 online players
                mockedBukkit.when(Bukkit::getOnlinePlayers).thenReturn(
                    java.util.Arrays.asList(mock(org.bukkit.entity.Player.class), mock(org.bukkit.entity.Player.class))
                );

                handler.handle(mockExchange);

                verify(mockExchange).sendResponseHeaders(eq(200), anyLong());
                String response = responseBody.toString(StandardCharsets.UTF_8);
                assertTrue(response.contains("\"status\":\"ok\""));
                assertTrue(response.contains("\"role\":\"lobby\""));
                assertTrue(response.contains("\"cycleNumber\":3"));
                assertTrue(response.contains("\"playersOnline\":2"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testHealthEndpointErrorHandling() throws Exception {
        int port = getNextPort();
        HttpRpcServer server = new HttpRpcServer(mockPlugin, port, "127.0.0.1");
        try {
            HttpRpcServer.HealthHandler handler = server.new HealthHandler();

            when(mockExchange.getRequestMethod()).thenReturn("GET");
            when(mockConfig.getString("server.role", "hardcore")).thenThrow(new RuntimeException("Config error"));

            handler.handle(mockExchange);

            verify(mockExchange).sendResponseHeaders(500, -1);
        } finally {
            server.stop(0);
        }
    }
}
