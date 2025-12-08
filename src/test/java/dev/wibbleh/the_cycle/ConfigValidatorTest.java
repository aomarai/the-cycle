package dev.wibbleh.the_cycle;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    @Test
    void testValidHardcoreConfig() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("server.http_enabled", true);
        cfg.set("server.http_port", 8080);
        cfg.set("server.rpc_secret", "supersecretkey123");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertFalse(result.hasErrors(), "Valid hardcore config should not have errors");
    }

    @Test
    void testValidLobbyConfig() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "lobby");
        cfg.set("server.hardcore", "hardcore-server");
        cfg.set("server.rpc_secret", "supersecretkey123");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertFalse(result.hasErrors(), "Valid lobby config should not have errors");
    }

    @Test
    void testInvalidServerRole() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "invalid");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasErrors(), "Invalid role should produce error");
        assertTrue(result.errors().getFirst().contains("Invalid server.role"));
    }

    @Test
    void testLobbyWithoutHardcoreConfig() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "lobby");
        cfg.set("server.hardcore", "");
        cfg.set("server.hardcore_http_url", "");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasErrors(), "Lobby without hardcore config should have error");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("server.hardcore")));
    }

    @Test
    void testInvalidHttpPort() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("server.http_enabled", true);
        cfg.set("server.http_port", 99999);

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasErrors(), "Invalid port should produce error");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("http_port")));
    }

    @Test
    void testHttpEnabledWithoutSecret() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("server.http_enabled", true);
        cfg.set("server.http_port", 8080);
        cfg.set("server.rpc_secret", "");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasWarnings(), "HTTP enabled without secret should produce warning");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("rpc_secret")));
    }

    @Test
    void testShortRpcSecret() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("server.http_enabled", true);
        cfg.set("server.http_port", 8080);
        cfg.set("server.rpc_secret", "short");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasWarnings(), "Short secret should produce warning");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("secret is short")));
    }

    @Test
    void testInvalidHttpUrl() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "lobby");
        cfg.set("server.hardcore", "");
        cfg.set("server.hardcore_http_url", "not-a-url");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasErrors(), "Invalid HTTP URL should produce error");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("hardcore_http_url")));
    }

    @Test
    void testInvalidWebhookUrl() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("webhook.url", "invalid-webhook");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasErrors(), "Invalid webhook URL should produce error");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("webhook.url")));
    }

    @Test
    void testValidHttpsUrls() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "lobby");
        cfg.set("server.hardcore_http_url", "https://hardcore.example.com:8080/rpc");
        cfg.set("webhook.url", "https://discord.com/api/webhooks/123/abc");

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertFalse(result.hasErrors(), "HTTPS URLs should be valid");
    }

    @Test
    void testExtremeCountdownValues() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("server.role", "hardcore");
        cfg.set("behavior.countdown_send_to_lobby_seconds", 500);
        cfg.set("behavior.countdown_send_to_hardcore_seconds", -10);

        ConfigValidator.ValidationResult result = ConfigValidator.validate(cfg);

        assertTrue(result.hasWarnings(), "Extreme countdown values should produce warnings");
    }

    @Test
    void testNullConfig() {
        ConfigValidator.ValidationResult result = ConfigValidator.validate(null);

        assertTrue(result.hasErrors(), "Null config should produce error");
        assertEquals("Configuration is null", result.errors().getFirst());
    }

    @Test
    void testValidationResultLogging() {
        ConfigValidator.ValidationResult result = new ConfigValidator.ValidationResult(
                java.util.List.of("Warning 1"),
                java.util.List.of("Error 1")
        );

        assertTrue(result.hasWarnings());
        assertTrue(result.hasErrors());

        // Should not throw exception
        result.logResults();
    }
}
