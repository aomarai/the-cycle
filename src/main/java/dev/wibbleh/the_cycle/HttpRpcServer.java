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
    public void stop(int delaySeconds) { server.stop(delaySeconds); }

    class RpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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
                    plugin.getLogger().warning("Rejected HTTP RPC with invalid signature.");
                    return;
                }
                // Expect a simple JSON like {"action":"cycle-now","caller":"..."}
                if (payload.contains("\"action\":\"cycle-now\"")) {
                    plugin.getLogger().info("Received HTTP RPC cycle-now; invoking triggerCycle.");
                    plugin.triggerCycle();
                    byte[] out = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
                    return;
                }
                exchange.sendResponseHeaders(400, -1);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to handle HTTP RPC: " + e.getMessage());
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }
}

