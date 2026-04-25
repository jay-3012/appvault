package com.appvault.developer;

import com.appvault.developer.dto.*;
import com.appvault.domain.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/developer/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    @PostMapping
    public ResponseEntity<AppResponse> createApp(
            @Valid @RequestBody CreateAppRequest request,
            @AuthenticationPrincipal User developer) {

        AppResponse response = appService.createApp(request, developer);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<AppResponse>> listApps(
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(appService.listApps(developer));
    }

    @GetMapping("/{appId}")
    public ResponseEntity<AppResponse> getApp(
            @PathVariable UUID appId,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(appService.getApp(appId, developer));
    }

    @PostMapping("/{appId}/versions")
    public ResponseEntity<VersionResponse> createVersion(
            @PathVariable UUID appId,
            @RequestParam("versionName") String versionName,
            @RequestParam(value = "changelog", required = false) String changelog,
            @RequestParam("apkFile") MultipartFile apkFile,
            @AuthenticationPrincipal User developer) throws Exception {

        CreateVersionRequest request = new CreateVersionRequest();
        request.setVersionName(versionName);
        request.setChangelog(changelog);

        VersionResponse response = appService.createVersion(
                appId, request, apkFile, developer);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{appId}/versions")
    public ResponseEntity<List<VersionResponse>> listVersions(
            @PathVariable UUID appId,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(appService.listVersions(appId, developer));
    }

    @PostMapping("/{appId}/versions/{versionId}/promote")
    public ResponseEntity<VersionResponse> promoteVersion(
            @PathVariable UUID appId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(
                appService.promoteVersion(appId, versionId, developer));
    }

    @PostMapping("/{appId}/versions/{versionId}/rollback")
    public ResponseEntity<VersionResponse> rollback(
            @PathVariable UUID appId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(
                appService.rollback(appId, versionId, developer));
    }

    @PostMapping("/{appId}/testers")
    public ResponseEntity<Map<String, String>> addTester(
            @PathVariable UUID appId,
            @Valid @RequestBody TesterRequest request,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(
                appService.addTester(appId, request, developer));
    }

    @GetMapping("/{appId}/testers")
    public ResponseEntity<List<Map<String, String>>> listTesters(
            @PathVariable UUID appId,
            @AuthenticationPrincipal User developer) {

        return ResponseEntity.ok(
                appService.listTesters(appId, developer));
    }
}
