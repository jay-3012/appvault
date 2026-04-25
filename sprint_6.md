# Sprint 6 — Admin Review + Release Tracks

Admin can review submitted apps, approve or reject with structured reasons,
and developers can promote versions through Alpha → Beta → Production tracks.

Follow every section in order.

---

## What you are building

```
Admin                    Spring Boot              Developer
  |                          |                        |
  | GET /admin/review-queue  |                        |
  |─────────────────────────►|                        |
  |  [list of SCAN_COMPLETE] |                        |
  |◄─────────────────────────|                        |
  |                          |                        |
  | POST /admin/apps/{id}/   |                        |
  |   versions/{vid}/approve |                        |
  |─────────────────────────►|                        |
  |                          | version → APPROVED     |
  |                          | track   → ALPHA        |
  |                          | notify developer       |
  |◄─────────────────────────|                        |
  |                          |                        |
  |                          |  POST /developer/apps/ |
  |                          |    {id}/versions/{vid}/|
  |                          |    promote             |
  |                          |◄───────────────────────|
  |                          | ALPHA → BETA → PROD    |
  |                          |───────────────────────►|
```

New endpoints:
- `GET  /admin/review-queue`                             — pending review list
- `GET  /admin/apps/{appId}/versions/{versionId}`        — full version detail
- `POST /admin/apps/{appId}/versions/{versionId}/approve`— approve → ALPHA
- `POST /admin/apps/{appId}/versions/{versionId}/reject` — reject with reason
- `PATCH /admin/apps/{appId}/content-rating`             — override rating
- `POST /admin/apps/{appId}/suspend`                     — suspend app
- `POST /admin/apps/{appId}/remove`                      — permanent removal
- `GET  /admin/audit-logs`                               — audit log
- `POST /developer/apps/{appId}/versions/{versionId}/promote`  — Alpha→Beta→Prod
- `POST /developer/apps/{appId}/versions/{versionId}/rollback` — revert to older version
- `POST /developer/apps/{appId}/testers`                 — add tester email
- `GET  /developer/apps/{appId}/testers`                 — list testers

---

## Step 1 — DB migration

Create `backend/src/main/resources/db/migration/V3__create_admin_tables.sql`:

```sql
CREATE TYPE rejection_reason AS ENUM (
    'MALWARE_DETECTED',
    'POLICY_VIOLATION',
    'CONTENT_MISMATCH',
    'INCOMPLETE_METADATA',
    'COPYRIGHT_ISSUE',
    'INVALID_APK'
);

CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID NOT NULL REFERENCES users(id),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id   VARCHAR(255) NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_admin_id ON audit_logs(admin_id);
CREATE INDEX idx_audit_logs_target_id ON audit_logs(target_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);

CREATE TABLE app_testers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id     UUID NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    track      release_track NOT NULL DEFAULT 'ALPHA',
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(app_id, email, track)
);

CREATE INDEX idx_app_testers_app_id ON app_testers(app_id);
CREATE INDEX idx_app_testers_email ON app_testers(email);
```

---

## Step 2 — updated file structure

```
backend/src/main/java/com/appvault/
├── domain/app/
│   ├── AuditLog.java               ← NEW entity
│   ├── AppTester.java              ← NEW entity
│   ├── AuditLogRepository.java     ← NEW
│   └── AppTesterRepository.java    ← NEW
├── admin/
│   ├── AdminController.java        ← NEW
│   ├── AdminService.java           ← NEW
│   └── dto/
│       ├── ReviewQueueItem.java    ← NEW
│       ├── RejectRequest.java      ← NEW
│       ├── ContentRatingOverride.java ← NEW
│       └── AuditLogResponse.java   ← NEW
└── developer/
    ├── AppController.java          ← UPDATE (add promote/rollback/testers)
    ├── AppService.java             ← UPDATE (add promote/rollback/testers)
    └── dto/
        └── TesterRequest.java      ← NEW
```

---

## Step 3 — AuditLog entity

Create `backend/src/main/java/com/appvault/domain/app/AuditLog.java`:

```java
package com.appvault.domain.app;

import com.appvault.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private User admin;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
```

---

## Step 4 — AppTester entity

Create `backend/src/main/java/com/appvault/domain/app/AppTester.java`:

```java
package com.appvault.domain.app;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_testers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppTester {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id", nullable = false)
    private App app;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "release_track")
    @Builder.Default
    private ReleaseTrack track = ReleaseTrack.ALPHA;

    @Column(name = "added_at")
    private OffsetDateTime addedAt;

    @PrePersist
    protected void onCreate() {
        addedAt = OffsetDateTime.now();
    }
}
```

---

## Step 5 — repositories

Create `backend/src/main/java/com/appvault/domain/app/AuditLogRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<AuditLog> findByTargetIdOrderByCreatedAtDesc(String targetId, Pageable pageable);
}
```

Create `backend/src/main/java/com/appvault/domain/app/AppTesterRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppTesterRepository extends JpaRepository<AppTester, UUID> {
    List<AppTester> findByAppId(UUID appId);
    List<AppTester> findByAppIdAndTrack(UUID appId, ReleaseTrack track);
    boolean existsByAppIdAndEmailAndTrack(UUID appId, String email, ReleaseTrack track);
    void deleteByAppIdAndEmail(UUID appId, String email);
}
```

---

## Step 6 — admin DTOs

Create `backend/src/main/java/com/appvault/admin/dto/ReviewQueueItem.java`:

```java
package com.appvault.admin.dto;

import com.appvault.domain.app.AppVersion;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ReviewQueueItem {
    private UUID versionId;
    private UUID appId;
    private String appTitle;
    private String packageName;
    private String developerEmail;
    private Integer versionCode;
    private String versionName;
    private Integer riskScore;
    private List<String> scanFlags;
    private List<String> permissions;
    private Integer minSdk;
    private Integer targetSdk;
    private String certFingerprint;
    private String contentRating;
    private Long apkSizeBytes;
    private OffsetDateTime submittedAt;

    public static ReviewQueueItem from(AppVersion v) {
        ReviewQueueItem item = new ReviewQueueItem();
        item.setVersionId(v.getId());
        item.setAppId(v.getApp().getId());
        item.setAppTitle(v.getApp().getTitle());
        item.setPackageName(v.getApp().getPackageName());
        item.setDeveloperEmail(v.getApp().getDeveloper().getEmail());
        item.setVersionCode(v.getVersionCode());
        item.setVersionName(v.getVersionName());
        item.setRiskScore(v.getRiskScore());
        item.setScanFlags(v.getScanFlags());
        item.setPermissions(v.getPermissions());
        item.setMinSdk(v.getMinSdk());
        item.setTargetSdk(v.getTargetSdk());
        item.setCertFingerprint(v.getCertFingerprint());
        item.setContentRating(v.getApp().getContentRating().name());
        item.setApkSizeBytes(v.getApkSizeBytes());
        item.setSubmittedAt(v.getCreatedAt());
        return item;
    }
}
```

Create `backend/src/main/java/com/appvault/admin/dto/RejectRequest.java`:

```java
package com.appvault.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectRequest {

    @NotBlank(message = "Rejection reason is required")
    private String reason;  // MALWARE_DETECTED | POLICY_VIOLATION | etc.

    private String notes;
}
```

Create `backend/src/main/java/com/appvault/admin/dto/ContentRatingOverride.java`:

```java
package com.appvault.admin.dto;

import com.appvault.domain.app.ContentRating;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ContentRatingOverride {

    @NotNull(message = "Content rating is required")
    private ContentRating contentRating;

    private String reason;
}
```

Create `backend/src/main/java/com/appvault/admin/dto/AuditLogResponse.java`:

```java
package com.appvault.admin.dto;

import com.appvault.domain.app.AuditLog;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AuditLogResponse {
    private UUID id;
    private String adminEmail;
    private String action;
    private String targetType;
    private String targetId;
    private String notes;
    private OffsetDateTime createdAt;

    public static AuditLogResponse from(AuditLog log) {
        AuditLogResponse r = new AuditLogResponse();
        r.setId(log.getId());
        r.setAdminEmail(log.getAdmin().getEmail());
        r.setAction(log.getAction());
        r.setTargetType(log.getTargetType());
        r.setTargetId(log.getTargetId());
        r.setNotes(log.getNotes());
        r.setCreatedAt(log.getCreatedAt());
        return r;
    }
}
```

---

## Step 7 — admin service

Create `backend/src/main/java/com/appvault/admin/AdminService.java`:

```java
package com.appvault.admin;

import com.appvault.admin.dto.*;
import com.appvault.domain.app.*;
import com.appvault.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final AppRepository appRepository;
    private final AppVersionRepository versionRepository;
    private final AuditLogRepository auditLogRepository;

    // ── Review queue ───────────────────────────────────────────────────────

    public List<ReviewQueueItem> getReviewQueue() {
        return versionRepository
                .findByStatusOrderByCreatedAtAsc(VersionStatus.SCAN_COMPLETE)
                .stream()
                .map(ReviewQueueItem::from)
                .collect(Collectors.toList());
    }

    public ReviewQueueItem getVersionDetail(UUID appId, UUID versionId) {
        AppVersion version = versionRepository
                .findByIdAndAppId(versionId, appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Version not found"));
        return ReviewQueueItem.from(version);
    }

    // ── Approve ────────────────────────────────────────────────────────────

    @Transactional
    public void approveVersion(UUID appId, UUID versionId,
                                User admin, String notes) {
        AppVersion version = getVersion(appId, versionId);

        if (version.getStatus() != VersionStatus.SCAN_COMPLETE) {
            throw new IllegalStateException(
                    "Only SCAN_COMPLETE versions can be approved. " +
                    "Current status: " + version.getStatus());
        }

        version.setStatus(VersionStatus.APPROVED);
        version.setTrack(ReleaseTrack.ALPHA);
        version.setIsActive(true);
        versionRepository.save(version);

        // Update app status to ACTIVE if it was DRAFT
        App app = version.getApp();
        if (app.getStatus() == AppStatus.DRAFT) {
            app.setStatus(AppStatus.ACTIVE);
            appRepository.save(app);
        }

        writeAuditLog(admin, "APPROVE_VERSION", "APP_VERSION",
                versionId.toString(), notes);

        log.info("Version approved: {} → ALPHA by admin: {}",
                versionId, admin.getEmail());
    }

    // ── Reject ─────────────────────────────────────────────────────────────

    @Transactional
    public void rejectVersion(UUID appId, UUID versionId,
                               RejectRequest request, User admin) {
        AppVersion version = getVersion(appId, versionId);

        if (version.getStatus() != VersionStatus.SCAN_COMPLETE) {
            throw new IllegalStateException(
                    "Only SCAN_COMPLETE versions can be rejected");
        }

        version.setStatus(VersionStatus.REJECTED);
        version.setRejectionReason(request.getReason());
        version.setRejectionNotes(request.getNotes());
        versionRepository.save(version);

        writeAuditLog(admin, "REJECT_VERSION", "APP_VERSION",
                versionId.toString(),
                "Reason: " + request.getReason() +
                (request.getNotes() != null ? " — " + request.getNotes() : ""));

        log.info("Version rejected: {} reason: {} by admin: {}",
                versionId, request.getReason(), admin.getEmail());
    }

    // ── Content rating override ────────────────────────────────────────────

    @Transactional
    public void overrideContentRating(UUID appId,
                                       ContentRatingOverride request,
                                       User admin) {
        App app = appRepository.findById(appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found: " + appId));

        ContentRating oldRating = app.getContentRating();
        app.setContentRating(request.getContentRating());
        appRepository.save(app);

        writeAuditLog(admin, "OVERRIDE_CONTENT_RATING", "APP",
                appId.toString(),
                "Changed from " + oldRating + " to " + request.getContentRating() +
                (request.getReason() != null ? ": " + request.getReason() : ""));

        log.info("Content rating overridden: app={} {} → {} by admin={}",
                appId, oldRating, request.getContentRating(), admin.getEmail());
    }

    // ── Suspend ────────────────────────────────────────────────────────────

    @Transactional
    public void suspendApp(UUID appId, String reason, User admin) {
        App app = appRepository.findById(appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found: " + appId));

        app.setStatus(AppStatus.SUSPENDED);
        appRepository.save(app);

        writeAuditLog(admin, "SUSPEND_APP", "APP",
                appId.toString(), reason);

        log.info("App suspended: {} by admin: {}", appId, admin.getEmail());
    }

    // ── Remove ─────────────────────────────────────────────────────────────

    @Transactional
    public void removeApp(UUID appId, String reason, User admin) {
        App app = appRepository.findById(appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("App not found: " + appId));

        app.setStatus(AppStatus.REMOVED);
        appRepository.save(app);

        writeAuditLog(admin, "REMOVE_APP", "APP",
                appId.toString(), reason);

        log.info("App removed: {} by admin: {}", appId, admin.getEmail());
    }

    // ── Audit logs ─────────────────────────────────────────────────────────

    public List<AuditLogResponse> getAuditLogs(int page, int size) {
        return auditLogRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .stream()
                .map(AuditLogResponse::from)
                .collect(Collectors.toList());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private AppVersion getVersion(UUID appId, UUID versionId) {
        return versionRepository
                .findByIdAndAppId(versionId, appId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Version not found"));
    }

    private void writeAuditLog(User admin, String action,
                                String targetType, String targetId,
                                String notes) {
        AuditLog log = AuditLog.builder()
                .admin(admin)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .notes(notes)
                .build();
        auditLogRepository.save(log);
    }
}
```

---

## Step 8 — admin controller

Create `backend/src/main/java/com/appvault/admin/AdminController.java`:

```java
package com.appvault.admin;

import com.appvault.admin.dto.*;
import com.appvault.domain.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/review-queue")
    public ResponseEntity<List<ReviewQueueItem>> getReviewQueue() {
        return ResponseEntity.ok(adminService.getReviewQueue());
    }

    @GetMapping("/apps/{appId}/versions/{versionId}")
    public ResponseEntity<ReviewQueueItem> getVersionDetail(
            @PathVariable UUID appId,
            @PathVariable UUID versionId) {
        return ResponseEntity.ok(
                adminService.getVersionDetail(appId, versionId));
    }

    @PostMapping("/apps/{appId}/versions/{versionId}/approve")
    public ResponseEntity<Map<String, String>> approveVersion(
            @PathVariable UUID appId,
            @PathVariable UUID versionId,
            @RequestParam(required = false) String notes,
            @AuthenticationPrincipal User admin) {

        adminService.approveVersion(appId, versionId, admin, notes);
        return ResponseEntity.ok(Map.of(
                "message", "Version approved and moved to ALPHA track"));
    }

    @PostMapping("/apps/{appId}/versions/{versionId}/reject")
    public ResponseEntity<Map<String, String>> rejectVersion(
            @PathVariable UUID appId,
            @PathVariable UUID versionId,
            @Valid @RequestBody RejectRequest request,
            @AuthenticationPrincipal User admin) {

        adminService.rejectVersion(appId, versionId, request, admin);
        return ResponseEntity.ok(Map.of(
                "message", "Version rejected",
                "reason", request.getReason()));
    }

    @PatchMapping("/apps/{appId}/content-rating")
    public ResponseEntity<Map<String, String>> overrideContentRating(
            @PathVariable UUID appId,
            @Valid @RequestBody ContentRatingOverride request,
            @AuthenticationPrincipal User admin) {

        adminService.overrideContentRating(appId, request, admin);
        return ResponseEntity.ok(Map.of(
                "message", "Content rating updated to " + request.getContentRating()));
    }

    @PostMapping("/apps/{appId}/suspend")
    public ResponseEntity<Map<String, String>> suspendApp(
            @PathVariable UUID appId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal User admin) {

        adminService.suspendApp(appId, reason, admin);
        return ResponseEntity.ok(Map.of("message", "App suspended"));
    }

    @PostMapping("/apps/{appId}/remove")
    public ResponseEntity<Map<String, String>> removeApp(
            @PathVariable UUID appId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal User admin) {

        adminService.removeApp(appId, reason, admin);
        return ResponseEntity.ok(Map.of("message", "App removed"));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLogResponse>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(adminService.getAuditLogs(page, size));
    }
}
```

---

## Step 9 — add missing query to AppVersionRepository

Add these two methods to `AppVersionRepository.java`:

```java
// Add these to the existing interface

List<AppVersion> findByStatusOrderByCreatedAtAsc(VersionStatus status);

List<AppVersion> findByAppIdAndTrackAndIsActiveTrueOrderByVersionCodeDesc(
        UUID appId, ReleaseTrack track);
```

---

## Step 10 — release tracks (developer side)

Add tester DTO:

Create `backend/src/main/java/com/appvault/developer/dto/TesterRequest.java`:

```java
package com.appvault.developer.dto;

import com.appvault.domain.app.ReleaseTrack;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TesterRequest {

    @NotBlank
    @Email(message = "Must be a valid email address")
    private String email;

    @NotNull
    private ReleaseTrack track;
}
```

Add these methods to `AppService.java`:

```java
// Add these imports at the top of AppService.java
import com.appvault.domain.app.AppTester;
import com.appvault.domain.app.AppTesterRepository;
import com.appvault.developer.dto.TesterRequest;

// Add field
private final AppTesterRepository testerRepository;

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
```

---

## Step 11 — add endpoints to AppController

Add these endpoints to `AppController.java`:

```java
// Add these imports
import com.appvault.developer.dto.TesterRequest;
import java.util.Map;

// Add these endpoints inside the class

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
```

---

## Step 12 — create an admin user in the database

You need at least one ADMIN-role user to test the admin endpoints.
After deploying, run this on main-vm:

```bash
# On main-vm
docker exec -it appvault-postgres psql -U appvault -d appvault

-- Create an admin user (password: admin123 — change in production)
-- BCrypt hash of "admin123"
INSERT INTO users (email, password_hash, role, status, full_name)
VALUES (
    'admin@appvault.online',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH',
    'ADMIN',
    'ACTIVE',
    'Platform Admin'
);

-- Verify
SELECT id, email, role, status FROM users WHERE role = 'ADMIN';
\q
```

The BCrypt hash above is for `admin123`. Generate a proper one for production:

```bash
# On your local machine
python3 -c "import bcrypt; print(bcrypt.hashpw(b'YOUR_ADMIN_PASSWORD', bcrypt.gensalt()).decode())"
# pip install bcrypt if needed
```

---

## Step 13 — commit and deploy

```bash
git add .
git commit -m "feat: Sprint 6 — admin review, approve/reject, release tracks, rollback, testers"
git push origin main
```

---

## Step 14 — end-to-end test

```bash
# ── Admin login ────────────────────────────────────────────────────────────
curl -X POST https://appvault.online/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@appvault.online","password":"admin123"}'

# Save ADMIN_TOKEN from response

# ── Review queue ────────────────────────────────────────────────────────────
curl https://appvault.online/admin/review-queue \
  -H "Authorization: Bearer ADMIN_TOKEN"
# Expected: list of versions with status SCAN_COMPLETE

# ── Approve a version ───────────────────────────────────────────────────────
curl -X POST https://appvault.online/admin/apps/APP_ID/versions/VERSION_ID/approve \
  -H "Authorization: Bearer ADMIN_TOKEN"
# Expected: {"message":"Version approved and moved to ALPHA track"}

# ── Check version is now ALPHA ──────────────────────────────────────────────
curl https://appvault.online/developer/apps/APP_ID/versions \
  -H "Authorization: Bearer DEV_TOKEN"
# Expected: version with status=APPROVED track=ALPHA isActive=true

# ── Promote ALPHA → BETA ────────────────────────────────────────────────────
curl -X POST https://appvault.online/developer/apps/APP_ID/versions/VERSION_ID/promote \
  -H "Authorization: Bearer DEV_TOKEN"
# Expected: version with track=BETA

# ── Promote BETA → PRODUCTION ───────────────────────────────────────────────
curl -X POST https://appvault.online/developer/apps/APP_ID/versions/VERSION_ID/promote \
  -H "Authorization: Bearer DEV_TOKEN"
# Expected: version with track=PRODUCTION isActive=true

# ── Try skipping tracks (should fail) ──────────────────────────────────────
# This requires a freshly approved version still on ALPHA
curl -X POST https://appvault.online/developer/apps/APP_ID/versions/VERSION_ID/promote \
  -H "Authorization: Bearer DEV_TOKEN"
# Promote twice in a row — second should go to PRODUCTION

# ── Reject a version ────────────────────────────────────────────────────────
curl -X POST https://appvault.online/admin/apps/APP_ID/versions/VERSION_ID/reject \
  -H "Authorization: Bearer ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"POLICY_VIOLATION","notes":"App contains prohibited content"}'
# Expected: {"message":"Version rejected","reason":"POLICY_VIOLATION"}

# ── Try reject without reason (should fail validation) ──────────────────────
curl -X POST https://appvault.online/admin/apps/APP_ID/versions/VERSION_ID/reject \
  -H "Authorization: Bearer ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
# Expected: 400 {"error":"Validation failed"}

# ── Override content rating ─────────────────────────────────────────────────
curl -X PATCH https://appvault.online/admin/apps/APP_ID/content-rating \
  -H "Authorization: Bearer ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentRating":"MATURE","reason":"Contains adult content"}'

# ── Add tester ──────────────────────────────────────────────────────────────
curl -X POST https://appvault.online/developer/apps/APP_ID/testers \
  -H "Authorization: Bearer DEV_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"tester@example.com","track":"ALPHA"}'

# ── List testers ────────────────────────────────────────────────────────────
curl https://appvault.online/developer/apps/APP_ID/testers \
  -H "Authorization: Bearer DEV_TOKEN"

# ── Suspend app ─────────────────────────────────────────────────────────────
curl -X POST "https://appvault.online/admin/apps/APP_ID/suspend?reason=Policy+violation" \
  -H "Authorization: Bearer ADMIN_TOKEN"

# ── Audit logs ──────────────────────────────────────────────────────────────
curl "https://appvault.online/admin/audit-logs?page=0&size=20" \
  -H "Authorization: Bearer ADMIN_TOKEN"
# Expected: list of every admin action with timestamp

# ── Non-admin accessing admin route (should fail) ───────────────────────────
curl https://appvault.online/admin/review-queue \
  -H "Authorization: Bearer DEV_TOKEN"
# Expected: 403 Forbidden
```

---

## Sprint 6 definition of done checklist

```
[ ] GET  /admin/review-queue returns SCAN_COMPLETE versions
[ ] POST /admin/apps/{id}/versions/{vid}/approve moves version to ALPHA
[ ] App status changes from DRAFT to ACTIVE on first approval
[ ] POST /admin/apps/{id}/versions/{vid}/reject requires reason field
[ ] Reject without reason returns 400 validation error
[ ] Rejected version cannot be promoted
[ ] Audit log entry written for every admin action
[ ] PATCH /admin/apps/{id}/content-rating updates rating
[ ] POST /admin/apps/{id}/suspend changes app status to SUSPENDED
[ ] Developer promote: ALPHA → BETA → PRODUCTION (sequential only)
[ ] Rollback: older PRODUCTION version becomes active
[ ] Add tester returns 200; duplicate tester returns 400
[ ] Non-admin token on /admin/* returns 403
[ ] Admin deployed successfully via GitHub Actions
```

---

## Troubleshooting

### "Only SCAN_COMPLETE versions can be approved"
The version is still in PENDING_SCAN — scanner has not finished yet.
Check scanner logs: `docker logs scanner-celery --tail 30`
Check if callback reached main platform: `docker logs appvault-app --tail 30 | grep callback`

### Admin endpoints return 403 even with admin token
The user in DB has role `ADMIN` but the JWT was issued before the role was set.
Log out, log back in — the new JWT will carry the ADMIN role claim.

### "Version is not in a promotable state"
The version must be APPROVED (by admin) before it can be promoted from ALPHA.
Check version status: `GET /developer/apps/{appId}/versions`

### BCrypt hash not working for admin user
Generate the correct hash:
```bash
python3 -c "
import bcrypt
pw = b'YOUR_PASSWORD'
print(bcrypt.hashpw(pw, bcrypt.gensalt(10)).decode())
"
```
Insert that hash directly into the DB.

### Unique constraint violation on promote
The DB constraint `idx_one_active_per_track` prevents two active versions
on the same track. The service deactivates the old one before activating
the new one — both must happen in the same transaction. If you see this
error it means the transaction rolled back partway. Check logs for the
root cause.