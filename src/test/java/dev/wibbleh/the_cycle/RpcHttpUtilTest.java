package dev.wibbleh.the_cycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcHttpUtilTest {

    @Test
    void testHmacComputeAndVerify() throws Exception {
        String secret = "s3cr3t";
        String payload = "{\"action\":\"cycle-now\",\"caller\":\"console\"}";

        String sig = RpcHttpUtil.computeHmacHex(secret, payload);
        assertNotNull(sig);
        assertFalse(sig.isEmpty());

        assertTrue(RpcHttpUtil.verifyHmacHex(secret, payload, sig));
        // wrong secret
        assertFalse(RpcHttpUtil.verifyHmacHex("other", payload, sig));
        // wrong payload
        assertFalse(RpcHttpUtil.verifyHmacHex(secret, payload + "x", sig));
    }
}
