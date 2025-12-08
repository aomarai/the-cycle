package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRetryUtilTest {

    @Test
    void testDefaultConfig() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.defaults();

        assertEquals(3, config.maxRetries());
        assertEquals(100, config.baseDelayMs());
        assertEquals(5000, config.maxDelayMs());
        assertEquals(3000, config.connectTimeoutMs());
        assertEquals(5000, config.readTimeoutMs());
    }

    @Test
    void testNoRetryConfig() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();

        assertEquals(0, config.maxRetries());
        assertEquals(0, config.baseDelayMs());
        assertEquals(0, config.maxDelayMs());
        assertEquals(3000, config.connectTimeoutMs());
        assertEquals(5000, config.readTimeoutMs());
    }

    @Test
    void testSuccessResult() {
        HttpRetryUtil.HttpResult result = HttpRetryUtil.HttpResult.success(200, 1);

        assertTrue(result.success());
        assertEquals(200, result.statusCode());
        assertNull(result.errorMessage());
        assertEquals(1, result.attempts());
    }

    @Test
    void testFailureResult() {
        HttpRetryUtil.HttpResult result = HttpRetryUtil.HttpResult.failure("Connection refused", 3);

        assertFalse(result.success());
        assertEquals(-1, result.statusCode());
        assertEquals("Connection refused", result.errorMessage());
        assertEquals(3, result.attempts());
    }

    @Test
    void testFailureResultWithStatusCode() {
        HttpRetryUtil.HttpResult result = HttpRetryUtil.HttpResult.failure(404, "Not Found", 2);

        assertFalse(result.success());
        assertEquals(404, result.statusCode());
        assertEquals("Not Found", result.errorMessage());
        assertEquals(2, result.attempts());
    }

    @Test
    void testPostWithRetryNullUrl() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry(null, "{}", null, config);

        assertFalse(result.success());
        assertEquals("URL is null or empty", result.errorMessage());
        assertEquals(0, result.attempts());
    }

    @Test
    void testPostWithRetryEmptyUrl() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry("", "{}", null, config);

        assertFalse(result.success());
        assertEquals("URL is null or empty", result.errorMessage());
        assertEquals(0, result.attempts());
    }

    @Test
    void testPostWithRetryNullPayload() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry("http://localhost", null, null, config);

        assertFalse(result.success());
        assertEquals("Payload is null", result.errorMessage());
        assertEquals(0, result.attempts());
    }

    @Test
    void testPostWithRetryInvalidUrl() {
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry("not-a-valid-url", "{}", null, config);

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertTrue(result.attempts() >= 1);
    }

    @Test
    void testPostWithRetryUnreachableHost() {
        // Using a non-routable IP address to ensure connection failure
        HttpRetryUtil.RetryConfig config = HttpRetryUtil.RetryConfig.noRetry();
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry("http://192.0.2.1:8080/rpc", "{}", null, config);

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
        assertEquals(1, result.attempts());
    }

    @Test
    void testPostWithRetryMultipleAttempts() {
        // Using non-routable IP to force retries
        HttpRetryUtil.RetryConfig config = new HttpRetryUtil.RetryConfig(2, 10, 100, 100, 100);
        HttpRetryUtil.HttpResult result = HttpRetryUtil.postWithRetry("http://192.0.2.1:8080/rpc", "{}", null, config);

        assertFalse(result.success());
        assertEquals(3, result.attempts()); // 1 initial + 2 retries
    }
}
