package com.appvault.scanner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class HmacVerifier {

    @Value("${scanner.hmac-secret}")
    private String hmacSecret;

    /**
     * Verifies the HMAC-SHA256 signature on an incoming scanner callback.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean verify(byte[] rawBody, String receivedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] expectedBytes = mac.doFinal(rawBody);
            String expectedHex = HexFormat.of().formatHex(expectedBytes);

            log.info("HMAC verify — received: {} expected: {}",
                    receivedSignature, expectedHex);

            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("HMAC verify error: {}", e.getMessage());
            return false;
        }
    }
}