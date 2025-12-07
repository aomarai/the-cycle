package dev.wibbleh.the_cycle;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Small utility for HMAC signing/verification used by the optional HTTP RPC transport.
 */
public final class RpcHttpUtil {
    private RpcHttpUtil() {}

    public static String computeHmacHex(String secret, String payload) throws Exception {
        if (secret == null) secret = "";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(sig);
    }

    public static boolean verifyHmacHex(String secret, String payload, String hexSignature) {
        try {
            if (hexSignature == null) return false;
            String expected = computeHmacHex(secret, payload);
            return constantTimeEquals(expected, hexSignature);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int res = 0;
        for (int i = 0; i < a.length(); i++) res |= a.charAt(i) ^ b.charAt(i);
        return res == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}

