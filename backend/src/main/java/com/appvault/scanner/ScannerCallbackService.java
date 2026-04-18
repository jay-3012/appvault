package com.appvault.scanner;

import com.appvault.scanner.dto.ScanResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScannerCallbackService {

    // AppVersionRepository and notification service will be wired in
    // Sprint 5 when the full submission flow is built.
    // For now we log the result and confirm the pipeline works.

    @Async
    public void processScanResult(ScanResultCallback callback) {
        log.info(
                "Processing scan result: appId={} versionId={} status={} score={} flags={}",
                callback.getAppId(),
                callback.getVersionId(),
                callback.getScanStatus(),
                callback.getRiskScore(),
                callback.getFlags());

        // Sprint 5 will:
        // 1. Update AppVersion status to SCAN_COMPLETE
        // 2. Store certFingerprint + permissions + riskScore on the version
        // 3. Fire notification to admin queue
        // Stub left intentionally — do not implement here.
    }
}