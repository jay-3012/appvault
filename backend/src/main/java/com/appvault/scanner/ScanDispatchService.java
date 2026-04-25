package com.appvault.scanner;

import com.appvault.domain.app.ScanJob;
import com.appvault.domain.app.ScanJobRepository;
import com.appvault.domain.app.AppVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanDispatchService {

    private final ScanJobRepository scanJobRepository;
    private final ObjectMapper objectMapper;

    @Value("${scanner.hmac-secret}")
    private String hmacSecret;

    @Value("${scanner.callback-url}")
    private String scannerUrl;

    @Async
    public void dispatchScanJob(AppVersion version, String gcsUrl) {
        String callbackId = UUID.randomUUID().toString();

        // Save scan job record
        ScanJob job = ScanJob.builder()
                .version(version)
                .callbackId(callbackId)
                .status("DISPATCHED")
                .build();
        scanJobRepository.save(job);

        try {
            Map<String, String> payload = Map.of(
                    "appId",      version.getApp().getId().toString(),
                    "versionId",  version.getId().toString(),
                    "apkUrl",     gcsUrl,
                    "callbackId", callbackId
            );

            String bodyJson = objectMapper.writeValueAsString(payload);
            String signature = sign(bodyJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(scannerUrl))
                    .header("Content-Type", "application/json")
                    .header("X-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 202) {
                log.info("Scan job dispatched: callbackId={} versionId={}",
                        callbackId, version.getId());
            } else {
                log.error("Scanner rejected job: HTTP {} — {}",
                        response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("Failed to dispatch scan job: {}", e.getMessage(), e);
        }
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
