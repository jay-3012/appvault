package com.appvault.developer;

import com.appvault.developer.dto.*;
import com.appvault.domain.app.*;
import com.appvault.domain.user.User;
import com.appvault.scanner.ScanDispatchService;
import com.appvault.storage.GcsStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
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
}
