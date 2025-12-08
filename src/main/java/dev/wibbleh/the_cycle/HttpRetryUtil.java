package dev.wibbleh.the_cycle;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Utility class for HTTP operations with retry logic and exponential backoff.
 * Provides resilient HTTP POST requests suitable for RPC communication.
 */
public final class HttpRetryUtil {
    private static final Logger LOG = Logger.getLogger("HardcoreCycle");
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_BASE_DELAY_MS = 100;
    private static final int DEFAULT_MAX_DELAY_MS = 5000;
    private static final Random RANDOM = new Random();

    private HttpRetryUtil() {
        // Utility class
    }

    /**
     * Configuration for HTTP retry behavior.
     */
    public record RetryConfig(
            int maxRetries,
            int baseDelayMs,
            int maxDelayMs,
            int connectTimeoutMs,
            int readTimeoutMs
    ) {
        /**
         * Create default retry configuration.
         */
        public static RetryConfig defaults() {
            return new RetryConfig(
                    DEFAULT_MAX_RETRIES,
                    DEFAULT_BASE_DELAY_MS,
                    DEFAULT_MAX_DELAY_MS,
                    3000,
                    5000
            );
        }

        /**
         * Create retry configuration without retries (single attempt).
         */
        public static RetryConfig noRetry() {
            return new RetryConfig(0, 0, 0, 3000, 5000);
        }
    }

    /**
     * Result of an HTTP POST operation.
     */
    public record HttpResult(
            boolean success,
            int statusCode,
            String errorMessage,
            int attempts
    ) {
        public static HttpResult success(int statusCode, int attempts) {
            return new HttpResult(true, statusCode, null, attempts);
        }

        public static HttpResult failure(String errorMessage, int attempts) {
            return new HttpResult(false, -1, errorMessage, attempts);
        }

        public static HttpResult failure(int statusCode, String errorMessage, int attempts) {
            return new HttpResult(false, statusCode, errorMessage, attempts);
        }
    }

    /**
     * Perform HTTP POST with retry and exponential backoff.
     *
     * @param url         target URL
     * @param payload     JSON payload to send
     * @param signature   HMAC signature header value (optional)
     * @param config      retry configuration
     * @return result of the HTTP operation
     */
    public static HttpResult postWithRetry(String url, String payload, String signature, RetryConfig config) {
        if (url == null || url.isEmpty()) {
            return HttpResult.failure("URL is null or empty", 0);
        }
        if (payload == null) {
            return HttpResult.failure("Payload is null", 0);
        }

        int attempts = 0;
        Exception lastException = null;

        for (int i = 0; i <= config.maxRetries; i++) {
            attempts++;

            try {
                URL u = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                if (signature != null && !signature.isEmpty()) {
                    conn.setRequestProperty("X-Signature", signature);
                }
                conn.setConnectTimeout(config.connectTimeoutMs);
                conn.setReadTimeout(config.readTimeoutMs);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    LOG.fine("HTTP POST to " + url + " succeeded with status " + code + " (attempt " + attempts + ")");
                    return HttpResult.success(code, attempts);
                } else if (code >= 400 && code < 500) {
                    // Client errors (4xx) are not retryable
                    String error = "HTTP POST returned " + code + " (client error, not retrying)";
                    LOG.warning(error);
                    return HttpResult.failure(code, error, attempts);
                } else {
                    // Server errors (5xx) are retryable
                    lastException = new Exception("HTTP " + code);
                    LOG.warning("HTTP POST to " + url + " returned " + code + " (attempt " + attempts + "/" + (config.maxRetries + 1) + ")");
                }

            } catch (Exception e) {
                lastException = e;
                LOG.warning("HTTP POST to " + url + " failed (attempt " + attempts + "/" + (config.maxRetries + 1) + "): " + e.getMessage());
            }

            // Don't sleep after the last attempt
            if (i < config.maxRetries) {
                int delay = calculateBackoff(i, config.baseDelayMs, config.maxDelayMs);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return HttpResult.failure("Interrupted during retry backoff", attempts);
                }
            }
        }

        String error = lastException != null ? lastException.getMessage() : "Unknown error";
        return HttpResult.failure("HTTP POST failed after " + attempts + " attempts: " + error, attempts);
    }

    /**
     * Calculate exponential backoff delay with jitter.
     *
     * @param attemptNumber zero-based attempt number (0 = first retry)
     * @param baseDelayMs   base delay in milliseconds
     * @param maxDelayMs    maximum delay in milliseconds
     * @return delay in milliseconds
     */
    private static int calculateBackoff(int attemptNumber, int baseDelayMs, int maxDelayMs) {
        // Exponential: delay = baseDelay * 2^attemptNumber
        int exponentialDelay = (int) (baseDelayMs * Math.pow(2, attemptNumber));
        // Cap at max delay
        int cappedDelay = Math.min(exponentialDelay, maxDelayMs);
        // Add jitter (up to 25% of the delay)
        int jitter = RANDOM.nextInt(cappedDelay / 4 + 1);
        return cappedDelay + jitter;
    }
}
