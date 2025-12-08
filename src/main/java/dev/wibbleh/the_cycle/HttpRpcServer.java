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
    private final Main plugin;
    private final HttpServer server;

    public HttpRpcServer(Main plugin, int port, String bindAddr) throws IOException {
        this.plugin = plugin;
        InetSocketAddress addr = bindAddr == null || bindAddr.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(bindAddr, port);
        server = HttpServer.create(addr, 0);
        server.createContext("/rpc", new RpcHandler());
        server.setExecutor(Executors.newFixedThreadPool(2));
    }

    public void start() { server.start(); }
    @SuppressWarnings("unused")
    public void stop(int delaySeconds) { server.stop(delaySeconds); }

    class RpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            final Logger safeLogger = plugin.getLogger();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try (InputStream is = exchange.getRequestBody()) {
                byte[] data = is.readAllBytes();
                String payload = new String(data, StandardCharsets.UTF_8);
                Headers hdr = exchange.getRequestHeaders();
                String sig = hdr.getFirst("X-Signature");
                String secret = plugin.getConfig().getString("server.rpc_secret", "");
                boolean ok = RpcHttpUtil.verifyHmacHex(secret, payload, sig);
                if (!ok) {
                    exchange.sendResponseHeaders(403, -1);
                    safeLogger.warning("Rejected HTTP RPC with invalid signature.");
                    return;
                }
                // Expect a simple JSON like {"action":"cycle-now","caller":"..."}
                if (payload.contains("\"action\":\"cycle-now\"")) {
                    safeLogger.info("Received HTTP RPC cycle-now; scheduling triggerCycle on main thread and waiting for completion.");
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>(null);
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
                        completed = latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    if (!completed) {
                        safeLogger.warning("HTTP RPC cycle-now did not complete within timeout; returning 202.");
                        byte[] out = "ACCEPTED".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(202, out.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
                        return;
                    }

                    if (err.get() != null) {
                        safeLogger.warning("HTTP RPC cycle-now completed with error: " + err.get().getMessage());
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }

                    // Completed successfully
                    byte[] out = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
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
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
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
                            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
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
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
                    return;
                }

                exchange.sendResponseHeaders(400, -1);
            } catch (Exception e) {
                safeLogger.warning("Failed to handle HTTP RPC: " + e.getMessage());
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }
}
