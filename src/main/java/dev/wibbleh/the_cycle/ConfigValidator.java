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
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int MIN_SECRET_LENGTH = 16;
    private static final int MAX_COUNTDOWN_SECONDS = 300;
    private static final int MAX_DELAY_SECONDS = 60;

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
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();

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
            if (httpPort < MIN_PORT || httpPort > MAX_PORT) {
                errors.add("Invalid server.http_port: " + httpPort + " (must be " + MIN_PORT + "-" + MAX_PORT + ")");
            }

            String rpcSecret = cfg.getString("server.rpc_secret", "").trim();
            if (rpcSecret.isEmpty()) {
                warnings.add("HTTP RPC is enabled but 'server.rpc_secret' is empty (authentication disabled)");
            } else if (rpcSecret.length() < MIN_SECRET_LENGTH) {
                warnings.add("RPC secret is short (" + rpcSecret.length() + " chars); recommend at least " + MIN_SECRET_LENGTH + " characters");
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

        if (countdownLobby < 0 || countdownLobby > MAX_COUNTDOWN_SECONDS) {
            warnings.add("countdown_send_to_lobby_seconds is " + countdownLobby + " (recommend 0-" + MAX_COUNTDOWN_SECONDS + ")");
        }
        if (countdownHardcore < 0 || countdownHardcore > MAX_COUNTDOWN_SECONDS) {
            warnings.add("countdown_send_to_hardcore_seconds is " + countdownHardcore + " (recommend 0-" + MAX_COUNTDOWN_SECONDS + ")");
        }
        if (delayGeneration < 0 || delayGeneration > MAX_DELAY_SECONDS) {
            warnings.add("delay_before_generation_seconds is " + delayGeneration + " (recommend 0-" + MAX_DELAY_SECONDS + ")");
        }
        if (waitPlayers < 0 || waitPlayers > MAX_COUNTDOWN_SECONDS) {
            warnings.add("wait_for_players_to_leave_seconds is " + waitPlayers + " (recommend 0-" + MAX_COUNTDOWN_SECONDS + ")");
        }

        // Validate webhook URL if configured
        String webhookUrl = cfg.getString("webhook.url", "").trim();
        if (!webhookUrl.isEmpty() && !webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
            errors.add("Invalid webhook.url: '" + webhookUrl + "' (must start with http:// or https://)");
        }

        return new ValidationResult(warnings, errors);
    }
}
