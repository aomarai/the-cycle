package dev.wibbleh.the_cycle;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import java.util.logging.Logger;

/**
 * Minimal embedded HTTP server used to accept RPCs on the hardcore backend.
 * It supports a POST /rpc endpoint that expects a JSON body and an HMAC header 'X-Signature'.
 */
public class HttpRpcServer {
    private static final int HTTP_OK = 200;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int RPC_TIMEOUT_SECONDS = 120;
    private static final int EXECUTOR_THREAD_COUNT = 2;
    
    private final Main plugin;
    private final HttpServer server;

    public HttpRpcServer(Main plugin, int port, String bindAddr) throws IOException {
        this.plugin = plugin;
        var addr = bindAddr == null || bindAddr.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bindAddr, port);
        server = HttpServer.create(addr, 0);
        server.createContext("/rpc", new RpcHandler());
        server.createContext("/health", new HealthHandler());
        server.setExecutor(Executors.newFixedThreadPool(EXECUTOR_THREAD_COUNT));
    }

    public void start() { server.start(); }
    @SuppressWarnings("unused")
    public void stop(int delaySeconds) { server.stop(delaySeconds); }

    class RpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            final var safeLogger = plugin.getLogger();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1);
                return;
            }
            try (var is = exchange.getRequestBody()) {
                byte[] data = is.readAllBytes();
                String payload = new String(data, StandardCharsets.UTF_8);
                var hdr = exchange.getRequestHeaders();
                String sig = hdr.getFirst("X-Signature");
                String secret = plugin.getConfig().getString("server.rpc_secret", "");
                boolean ok = RpcHttpUtil.verifyHmacHex(secret, payload, sig);
                if (!ok) {
                    exchange.sendResponseHeaders(HTTP_FORBIDDEN, -1);
                    safeLogger.warning("Rejected HTTP RPC with invalid signature.");
                    return;
                }
                // Expect a simple JSON like {"action":"cycle-now","caller":"..."}
                if (payload.contains("\"action\":\"cycle-now\"")) {
                    safeLogger.info("Received HTTP RPC cycle-now; scheduling triggerCycle on main thread and waiting for completion.");
                    final var latch = new java.util.concurrent.CountDownLatch(1);
                    final var err = new java.util.concurrent.atomic.AtomicReference<Throwable>(null);
                    // Schedule the work on main thread and count down when finished
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            plugin.triggerCycle();
                        } catch (Throwable t) {
                            safeLogger.warning("Error while executing triggerCycle from HTTP RPC: " + t.getMessage());
                            err.set(t);
                        } finally {
                            latch.countDown();
                        }
                    });

                    // Wait for completion (timeout to avoid hanging indefinitely)
                    boolean completed = false;
                    try {
                        // Wait longer for large worlds; 120s should be ample for typical cases while avoiding indefinite block.
                        completed = latch.await(RPC_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    if (!completed) {
                        safeLogger.warning("HTTP RPC cycle-now did not complete within timeout; returning 202.");
                        byte[] out = "ACCEPTED".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(HTTP_ACCEPTED, out.length);
                        try (var os = exchange.getResponseBody()) { os.write(out); }
                        return;
                    }

                    if (err.get() != null) {
                        safeLogger.warning("HTTP RPC cycle-now completed with error: " + err.get().getMessage());
                        exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, -1);
                        return;
                    }

                    // Completed successfully
                    byte[] out = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(HTTP_OK, out.length);
                    try (var os = exchange.getResponseBody()) { os.write(out); }
                    return;
                }

                // world-ready: backend notifies lobby that the new world is ready; lobby should move players to hardcore
                if (payload.contains("\"action\":\"world-ready\"")) {
                    safeLogger.info("Received HTTP world-ready notification; scheduling lobby countdown to move players to hardcore.");
                    // Schedule the lobby-side countdown to move players to hardcore. Run on main thread to be safe.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            int secs = plugin.getCountdownSendToHardcoreSeconds();
                            plugin.scheduleCountdownThenMovePlayersToHardcore(secs);
                        } catch (Throwable t) {
                            safeLogger.warning("Error while scheduling lobby move after world-ready: " + t.getMessage());
                        }
                    });
                    byte[] out = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(HTTP_OK, out.length);
                    try (var os = exchange.getResponseBody()) { os.write(out); }
                    return;
                }

                // move-players: explicit request to move players to configured hardcore server
                if (payload.contains("\"action\":\"move-players\"")) {
                    safeLogger.info("Received HTTP move-players; scheduling player move on main thread.");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            String target = plugin.getHardcoreServerName();
                            if (target == null || target.isEmpty()) {
                                safeLogger.warning("move-players received but hardcore server name is not configured.");
                                return;
                            }
                            for (var p : Bukkit.getOnlinePlayers()) {
                                try {
                                    plugin.sendPlayerToServer(p, target);
                                } catch (Exception ex) {
                                    safeLogger.warning("Failed to move player " + p.getName() + " to " + target + ": " + ex.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            safeLogger.warning("Error while processing move-players: " + t.getMessage());
                        }
                    });
                    byte[] out = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(HTTP_OK, out.length);
                    try (var os = exchange.getResponseBody()) { os.write(out); }
                    return;
                }

                exchange.sendResponseHeaders(HTTP_BAD_REQUEST, -1);
            } catch (Exception e) {
                safeLogger.warning("Failed to handle HTTP RPC: " + e.getMessage());
                exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, -1);
            }
        }
    }

    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1);
                return;
            }

            try {
                String role = plugin.getConfig().getString("server.role", "hardcore");
                int cycle = plugin.getCycleNumber();
                int players = Bukkit.getOnlinePlayers().size();

                String response = String.format(
                        "{\"status\":\"ok\",\"role\":\"%s\",\"cycleNumber\":%d,\"playersOnline\":%d}",
                        role, cycle, players
                );

                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(HTTP_OK, responseBytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to handle health check: " + e.getMessage());
                exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, -1);
            }
        }
    }
}
