package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            try {
                HttpClient client = HttpClient.newBuilder().build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) plugin.getLogger().info("Webhook POST returned " + code);
                else plugin.getLogger().warning("Webhook POST returned non-2xx code " + code);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send webhook: " + e.getMessage());
            }
        });
    }
}
