package dev.wibbleh.the_cycle;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Validates plugin configuration on startup to catch common misconfigurations early.
 * This helps prevent runtime failures due to invalid or incomplete configuration.
 */
public final class ConfigValidator {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");

    private ConfigValidator() {
        // Utility class
    }

    /**
     * Validation result containing warnings and errors.
     */
    public record ValidationResult(
            List<String> warnings,
            List<String> errors
    ) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public void logResults() {
            for (String warning : warnings) {
                LOG.warning("[Config] " + warning);
            }
            for (String error : errors) {
                LOG.severe("[Config] " + error);
            }
        }
    }

    /**
     * Validate plugin configuration.
     *
     * @param cfg configuration to validate
     * @return validation result with warnings and errors
     */
    public static ValidationResult validate(FileConfiguration cfg) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (cfg == null) {
            errors.add("Configuration is null");
            return new ValidationResult(warnings, errors);
        }

        // Validate server role
        String role = cfg.getString("server.role", "hardcore").trim().toLowerCase();
        if (!role.equals("hardcore") && !role.equals("lobby")) {
            errors.add("Invalid server.role: '" + role + "' (must be 'hardcore' or 'lobby')");
        }

        // Validate lobby-specific configuration
        if (role.equals("lobby")) {
            String hardcoreServer = cfg.getString("server.hardcore", "").trim();
            String hardcoreHttpUrl = cfg.getString("server.hardcore_http_url", "").trim();

            if (hardcoreServer.isEmpty() && hardcoreHttpUrl.isEmpty()) {
                errors.add("Lobby server must have either 'server.hardcore' or 'server.hardcore_http_url' configured");
            }

            if (!hardcoreHttpUrl.isEmpty() && !hardcoreHttpUrl.startsWith("http://") && !hardcoreHttpUrl.startsWith("https://")) {
                errors.add("Invalid server.hardcore_http_url: '" + hardcoreHttpUrl + "' (must start with http:// or https://)");
            }
        }

        // Validate HTTP RPC configuration
        boolean httpEnabled = cfg.getBoolean("server.http_enabled", false);
        if (httpEnabled) {
            int httpPort = cfg.getInt("server.http_port", 8080);
            if (httpPort < 1 || httpPort > 65535) {
                errors.add("Invalid server.http_port: " + httpPort + " (must be 1-65535)");
            }

            String rpcSecret = cfg.getString("server.rpc_secret", "").trim();
            if (rpcSecret.isEmpty()) {
                warnings.add("HTTP RPC is enabled but 'server.rpc_secret' is empty (authentication disabled)");
            } else if (rpcSecret.length() < 16) {
                warnings.add("RPC secret is short (" + rpcSecret.length() + " chars); recommend at least 16 characters");
            }
        }

        // Validate hardcore-specific HTTP notification
        if (role.equals("hardcore")) {
            String lobbyHttpUrl = cfg.getString("server.lobby_http_url", "").trim();
            if (!lobbyHttpUrl.isEmpty() && !lobbyHttpUrl.startsWith("http://") && !lobbyHttpUrl.startsWith("https://")) {
                errors.add("Invalid server.lobby_http_url: '" + lobbyHttpUrl + "' (must start with http:// or https://)");
            }
        }

        // Validate behavior settings
        int countdownLobby = cfg.getInt("behavior.countdown_send_to_lobby_seconds", 10);
        int countdownHardcore = cfg.getInt("behavior.countdown_send_to_hardcore_seconds", 10);
        int delayGeneration = cfg.getInt("behavior.delay_before_generation_seconds", 3);
        int waitPlayers = cfg.getInt("behavior.wait_for_players_to_leave_seconds", 30);

        if (countdownLobby < 0 || countdownLobby > 300) {
            warnings.add("countdown_send_to_lobby_seconds is " + countdownLobby + " (recommend 0-300)");
        }
        if (countdownHardcore < 0 || countdownHardcore > 300) {
            warnings.add("countdown_send_to_hardcore_seconds is " + countdownHardcore + " (recommend 0-300)");
        }
        if (delayGeneration < 0 || delayGeneration > 60) {
            warnings.add("delay_before_generation_seconds is " + delayGeneration + " (recommend 0-60)");
        }
        if (waitPlayers < 0 || waitPlayers > 300) {
            warnings.add("wait_for_players_to_leave_seconds is " + waitPlayers + " (recommend 0-300)");
        }

        // Validate webhook URL if configured
        String webhookUrl = cfg.getString("webhook.url", "").trim();
        if (!webhookUrl.isEmpty() && !webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
            errors.add("Invalid webhook.url: '" + webhookUrl + "' (must start with http:// or https://)");
        }

        return new ValidationResult(warnings, errors);
    }
}
