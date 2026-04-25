package com.appvault.developer;

import com.appvault.developer.dto.*;
import com.appvault.domain.app.*;
import com.appvault.domain.user.User;
import com.appvault.scanner.ScanDispatchService;
import com.appvault.storage.GcsStorageService;
import com.appvault.domain.app.AppTester;
import com.appvault.domain.app.AppTesterRepository;
import com.appvault.developer.dto.TesterRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppService {

    private final AppRepository appRepository;
    private final AppVersionRepository versionRepository;
    private final AppOwnershipRepository ownershipRepository;
    private final GcsStorageService storageService;
    private final ScanDispatchService scanDispatchService;
    private final AppTesterRepository testerRepository;

    @Transactional
    public AppResponse createApp(CreateAppRequest request, User developer) {
        App app = App.builder()
                .developer(developer)
                .title(request.getTitle())
                .shortDescription(request.getShortDescription())
                .fullDescription(request.getFullDescription())
                .category(request.getCategory())
                .contentRating(request.getContentRating())
                .status(AppStatus.DRAFT)
                .build();

        appRepository.save(app);
        log.info("App created: {} by developer: {}", app.getId(), developer.getEmail());
        return AppResponse.from(app);
    }

    public List<AppResponse> listApps(User developer) {
        return appRepository
                .findByDeveloperIdOrderByCreatedAtDesc(developer.getId())
                .stream()
                .map(AppResponse::from)
                .collect(Collectors.toList());
    }

    public AppResponse getApp(UUID appId, User developer) {
        App app = appRepository
                .findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));
        return AppResponse.from(app);
    }

    @Transactional
    public VersionResponse createVersion(UUID appId,
                                          CreateVersionRequest request,
                                          MultipartFile apkFile,
                                          User developer) throws Exception {

        // Verify app belongs to this developer
        App app = appRepository
                .findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        // Upload APK to GCS first
        String versionId = UUID.randomUUID().toString();
        String apkGcsPath = storageService.uploadApk(
                appId.toString(), versionId, apkFile);
        String gcsUrl = storageService.getGcsUrl(apkGcsPath);

        // Get highest existing version code for this app
        Integer maxVersionCode = versionRepository
                .findMaxVersionCodeByAppId(appId)
                .orElse(0);

        // Build version record — metadata filled in by scanner callback
        AppVersion version = AppVersion.builder()
                .app(app)
                .versionCode(maxVersionCode + 1)
                .versionName(request.getVersionName())
                .changelog(request.getChangelog())
                .apkGcsPath(apkGcsPath)
                .apkSizeBytes(apkFile.getSize())
                .status(VersionStatus.PENDING_SCAN)
                .build();

        versionRepository.save(version);

        // Dispatch scan job async — does not block this request
        scanDispatchService.dispatchScanJob(version, gcsUrl);

        log.info("Version created and scan dispatched: appId={} versionId={}",
                appId, version.getId());

        return VersionResponse.from(version);
    }

    public List<VersionResponse> listVersions(UUID appId, User developer) {
        // Verify ownership
        appRepository.findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        return versionRepository
                .findByAppIdOrderByVersionCodeDesc(appId)
                .stream()
                .map(VersionResponse::from)
                .collect(Collectors.toList());
    }

    // ── Promote version ────────────────────────────────────────────────────

    @Transactional
    public VersionResponse promoteVersion(UUID appId, UUID versionId,
                                           User developer) {
        App app = appRepository.findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        AppVersion version = versionRepository
                .findByIdAndAppId(versionId, appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Version not found"));

        // Determine next track
        ReleaseTrack nextTrack = nextTrack(version.getTrack(), version.getStatus());

        // Deactivate current active version on the next track
        versionRepository
                .findByAppIdAndTrackAndIsActiveTrueOrderByVersionCodeDesc(
                        appId, nextTrack)
                .forEach(v -> {
                    v.setIsActive(false);
                    v.setStatus(VersionStatus.SUPERSEDED);
                    versionRepository.save(v);
                });

        // Promote
        version.setTrack(nextTrack);
        version.setIsActive(true);
        versionRepository.save(version);

        log.info("Version promoted: {} → {} appId={}", versionId, nextTrack, appId);
        return VersionResponse.from(version);
    }

    private ReleaseTrack nextTrack(ReleaseTrack current, VersionStatus status) {
        if (status == VersionStatus.APPROVED && current == ReleaseTrack.ALPHA) {
            return ReleaseTrack.BETA;
        }
        if (current == ReleaseTrack.ALPHA && status != VersionStatus.APPROVED) {
            throw new IllegalStateException(
                    "Version must be APPROVED before promotion");
        }
        if (current == ReleaseTrack.BETA) {
            return ReleaseTrack.PRODUCTION;
        }
        if (current == ReleaseTrack.PRODUCTION) {
            throw new IllegalStateException(
                    "Version is already in PRODUCTION");
        }
        throw new IllegalStateException(
                "Version is not in a promotable state. " +
                "Current track: " + current + ", status: " + status);
    }

    // ── Rollback ────────────────────────────────────────────────────────────

    @Transactional
    public VersionResponse rollback(UUID appId, UUID targetVersionId,
                                     User developer) {
        appRepository.findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        AppVersion targetVersion = versionRepository
                .findByIdAndAppId(targetVersionId, appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Version not found"));

        if (targetVersion.getTrack() != ReleaseTrack.PRODUCTION) {
            throw new IllegalStateException(
                    "Rollback only applies to PRODUCTION versions");
        }

        // Deactivate all currently active production versions
        versionRepository
                .findByAppIdAndTrackAndIsActiveTrueOrderByVersionCodeDesc(
                        appId, ReleaseTrack.PRODUCTION)
                .forEach(v -> {
                    v.setIsActive(false);
                    versionRepository.save(v);
                });

        // Activate the target version
        targetVersion.setIsActive(true);
        versionRepository.save(targetVersion);

        log.info("Rollback: appId={} → versionId={}", appId, targetVersionId);
        return VersionResponse.from(targetVersion);
    }

    // ── Testers ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, String> addTester(UUID appId, TesterRequest request,
                                          User developer) {
        appRepository.findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        if (testerRepository.existsByAppIdAndEmailAndTrack(
                appId, request.getEmail(), request.getTrack())) {
            throw new IllegalArgumentException(
                    "Tester already added for this track");
        }

        App app = appRepository.findById(appId).orElseThrow();
        AppTester tester = AppTester.builder()
                .app(app)
                .email(request.getEmail())
                .track(request.getTrack())
                .build();
        testerRepository.save(tester);

        return Map.of("message",
                "Tester added: " + request.getEmail() +
                " for track " + request.getTrack());
    }

    public List<Map<String, String>> listTesters(UUID appId, User developer) {
        appRepository.findByIdAndDeveloperId(appId, developer.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found or access denied"));

        return testerRepository.findByAppId(appId)
                .stream()
                .map(t -> Map.of(
                        "email", t.getEmail(),
                        "track", t.getTrack().name(),
                        "addedAt", t.getAddedAt().toString()
                ))
                .collect(Collectors.toList());
    }
}
