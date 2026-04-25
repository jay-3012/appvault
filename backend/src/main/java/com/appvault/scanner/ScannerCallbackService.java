package com.appvault.scanner;

import com.appvault.domain.app.*;
import com.appvault.scanner.dto.ScanResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScannerCallbackService {

    private final AppVersionRepository versionRepository;
    private final AppOwnershipRepository ownershipRepository;
    private final AppRepository appRepository;
    private final ScanJobRepository scanJobRepository;

    @Async
    @Transactional
    public void processScanResult(ScanResultCallback callback) {
        log.info("Processing scan: versionId={} status={} score={}",
                callback.getVersionId(),
                callback.getScanStatus(),
                callback.getRiskScore());

        // Find the version
        AppVersion version = versionRepository
                .findById(java.util.UUID.fromString(callback.getVersionId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Version not found: " + callback.getVersionId()));

        // Update version with scan results
        version.setRiskScore(callback.getRiskScore());
        version.setScanFlags(callback.getFlags());
        version.setCertFingerprint(callback.getCertFingerprint());
        version.setPermissions(callback.getPermissions());
        version.setMinSdk(callback.getMinSdk());
        version.setTargetSdk(callback.getTargetSdk());
        version.setStatus(VersionStatus.SCAN_COMPLETE);

        // Update the app's package name from scan result
        App app = version.getApp();
        if (app.getPackageName() == null && callback.getPackageName() != null) {
            app.setPackageName(callback.getPackageName());
            appRepository.save(app);

            // Register ownership if not already registered
            ownershipRepository.findByPackageName(callback.getPackageName())
                    .ifPresentOrElse(
                            existing -> log.info("Ownership already registered for: {}",
                                    callback.getPackageName()),
                            () -> {
                                AppOwnership ownership = AppOwnership.builder()
                                        .packageName(callback.getPackageName())
                                        .certFingerprint(callback.getCertFingerprint())
                                        .developer(app.getDeveloper())
                                        .build();
                                ownershipRepository.save(ownership);
                                log.info("Ownership registered: {} → developer {}",
                                        callback.getPackageName(),
                                        app.getDeveloper().getEmail());
                            }
                    );
        }

        versionRepository.save(version);

        // Update scan job status
        scanJobRepository.findByCallbackId(callback.getCallbackId())
                .ifPresent(job -> {
                    job.setStatus("COMPLETED");
                    job.setCompletedAt(OffsetDateTime.now());
                    scanJobRepository.save(job);
                });

        log.info("Scan result processed: versionId={} finalStatus={}",
                callback.getVersionId(), version.getStatus());
    }
}