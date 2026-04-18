package com.appvault.scanner;

import com.appvault.scanner.dto.ScanResultCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/internal/scanner-callback")
@RequiredArgsConstructor
@Slf4j
public class ScannerCallbackController {

        private final HmacVerifier hmacVerifier;
        private final ScannerCallbackService callbackService;
        private final StringRedisTemplate redisTemplate;
        private final ObjectMapper objectMapper;

        @PostMapping
        public ResponseEntity<Map<String, String>> handleCallback(
                        HttpServletRequest request) throws Exception {

                // Read raw body BEFORE Jackson touches it
                byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());

                // Verify HMAC signature
                String signature = request.getHeader("X-Signature");
                if (signature == null || signature.isBlank()) {
                        log.warn("Scanner callback missing X-Signature header");
                        return ResponseEntity.status(401)
                                        .body(Map.of("error", "Missing signature"));
                }

                if (!hmacVerifier.verify(rawBody, signature)) {
                        log.warn("Scanner callback HMAC verification failed");
                        return ResponseEntity.status(401)
                                        .body(Map.of("error", "Invalid signature"));
                }

                // Parse body after verification
                ScanResultCallback callback = objectMapper.readValue(
                                rawBody, ScanResultCallback.class);

                // Replay protection — check if this callbackId was already processed
                String replayKey = "scanner:callback:" + callback.getCallbackId();
                Boolean isNew = redisTemplate.opsForValue()
                                .setIfAbsent(replayKey, "1", 24, TimeUnit.HOURS);

                if (Boolean.FALSE.equals(isNew)) {
                        log.info("Duplicate callback ignored: {}", callback.getCallbackId());
                        return ResponseEntity.ok(Map.of("message", "Already processed"));
                }

                // Process the scan result async
                callbackService.processScanResult(callback);

                log.info("Scan callback accepted: appId={} status={} score={}",
                                callback.getAppId(),
                                callback.getScanStatus(),
                                callback.getRiskScore());

                return ResponseEntity.accepted()
                                .body(Map.of("message", "Scan result accepted"));
        }
}