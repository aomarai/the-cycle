package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Helper service that sends JSON payloads to a configured webhook URL.
 * Posts run asynchronously on the server scheduler to avoid blocking the main thread.
 */
public class WebhookService {
    private final JavaPlugin plugin;
    private final String webhookUrl;

    /**
     * Construct a WebhookService.
     *
     * @param plugin     plugin instance used for scheduling and logging
     * @param webhookUrl target URL for POST requests (may be empty/null to disable)
     */
    public WebhookService(JavaPlugin plugin, String webhookUrl) {
        this.plugin = plugin;
        this.webhookUrl = webhookUrl;
    }

    /**
     * Send a JSON payload to the configured webhook URL asynchronously.
     * If the webhook URL is missing this is a no-op.
     *
     * @param payload JSON payload string to POST
     */
    public void send(String payload) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(webhookUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                byte[] out = payload.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) plugin.getLogger().info("Webhook POST returned " + code);
                else plugin.getLogger().warning("Webhook POST returned non-2xx code " + code);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send webhook: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
