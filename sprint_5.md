# Sprint 5 — App Submission API

First sprint where everything connects: GCS storage, APK parser,
ownership binding, scanner dispatch, and callback processing.

Follow every section in order.

---

## What you are building

```
Developer                Spring Boot              Scanner VM
    |                        |                        |
    | POST /developer/apps   |                        |
    |───────────────────────►|                        |
    |                        | save app to DB         |
    |                        |                        |
    | POST /developer/        |                        |
    |   apps/{id}/versions   |                        |
    |───────────────────────►|                        |
    |                        | upload APK to GCS      |
    |                        | call /parse/upload      |
    |                        | ownership check        |
    |                        | dispatch scan job      |
    |                        |───────────────────────►|
    | 202 version created    |                        | analyze APK
    |◄───────────────────────|                        |
    |                        |  POST /internal/        |
    |                        |    scanner-callback    |
    |                        |◄───────────────────────|
    |                        | update version status  |
```

New endpoints on main platform:
- `POST /developer/apps`                          — create app listing
- `GET  /developer/apps`                          — list developer's apps
- `GET  /developer/apps/{appId}`                  — get single app
- `POST /developer/apps/{appId}/versions`         — upload APK + metadata
- `GET  /developer/apps/{appId}/versions`         — list versions
- `POST /internal/scanner-callback`               — already built Sprint 4, now fully wired

---

## Step 1 — DB migration

Create `backend/src/main/resources/db/migration/V2__create_apps_tables.sql`:

```sql
CREATE TYPE app_status AS ENUM ('DRAFT', 'ACTIVE', 'SUSPENDED', 'REMOVED');
CREATE TYPE version_status AS ENUM (
    'PENDING_SCAN',
    'SCAN_COMPLETE',
    'APPROVED',
    'REJECTED',
    'SUPERSEDED'
);
CREATE TYPE release_track AS ENUM ('ALPHA', 'BETA', 'PRODUCTION');
CREATE TYPE content_rating AS ENUM ('EVERYONE', 'TEEN', 'MATURE');

CREATE TABLE apps (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    developer_id     UUID NOT NULL REFERENCES users(id),
    title            VARCHAR(255) NOT NULL,
    short_description VARCHAR(500),
    full_description TEXT,
    category         VARCHAR(100),
    content_rating   content_rating NOT NULL DEFAULT 'EVERYONE',
    status           app_status NOT NULL DEFAULT 'DRAFT',
    icon_gcs_path    VARCHAR(500),
    banner_gcs_path  VARCHAR(500),
    package_name     VARCHAR(255),
    average_rating   DECIMAL(3,2) DEFAULT 0.00,
    rating_count     INTEGER DEFAULT 0,
    total_downloads  BIGINT DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_apps_developer_id ON apps(developer_id);
CREATE INDEX idx_apps_status ON apps(status);
CREATE INDEX idx_apps_category ON apps(category);

CREATE TABLE app_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id            UUID NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    version_code      INTEGER NOT NULL,
    version_name      VARCHAR(100) NOT NULL,
    changelog         TEXT,
    apk_gcs_path      VARCHAR(500),
    apk_size_bytes    BIGINT,
    min_sdk           INTEGER,
    target_sdk        INTEGER,
    cert_fingerprint  VARCHAR(500),
    permissions       TEXT[],
    status            version_status NOT NULL DEFAULT 'PENDING_SCAN',
    track             release_track,
    is_active         BOOLEAN DEFAULT FALSE,
    risk_score        INTEGER,
    scan_flags        TEXT[],
    rejection_reason  VARCHAR(100),
    rejection_notes   TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_versions_app_id ON app_versions(app_id);
CREATE INDEX idx_app_versions_status ON app_versions(status);

-- Ensure only one active version per track per app
CREATE UNIQUE INDEX idx_one_active_per_track
    ON app_versions(app_id, track)
    WHERE is_active = TRUE AND track IS NOT NULL;

CREATE TABLE app_ownership (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_name      VARCHAR(255) NOT NULL UNIQUE,
    cert_fingerprint  VARCHAR(500) NOT NULL,
    developer_id      UUID NOT NULL REFERENCES users(id),
    registered_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_ownership_package ON app_ownership(package_name);
CREATE INDEX idx_app_ownership_developer ON app_ownership(developer_id);

CREATE TABLE scan_jobs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id    UUID NOT NULL REFERENCES app_versions(id),
    callback_id   VARCHAR(255) NOT NULL UNIQUE,
    status        VARCHAR(50) NOT NULL DEFAULT 'DISPATCHED',
    dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_scan_jobs_callback_id ON scan_jobs(callback_id);
CREATE INDEX idx_scan_jobs_version_id ON scan_jobs(version_id);
```

---

## Step 2 — updated backend file structure

```
backend/src/main/java/com/appvault/
├── domain/
│   ├── user/                         ← Sprint 2, no changes
│   └── app/
│       ├── App.java                  ← NEW entity
│       ├── AppStatus.java            ← NEW enum
│       ├── AppVersion.java           ← NEW entity
│       ├── VersionStatus.java        ← NEW enum
│       ├── ReleaseTrack.java         ← NEW enum
│       ├── ContentRating.java        ← NEW enum
│       ├── AppOwnership.java         ← NEW entity
│       ├── ScanJob.java              ← NEW entity
│       ├── AppRepository.java        ← NEW
│       ├── AppVersionRepository.java ← NEW
│       ├── AppOwnershipRepository.java ← NEW
│       └── ScanJobRepository.java    ← NEW
├── developer/
│   ├── AppController.java            ← NEW
│   ├── AppService.java               ← NEW
│   └── dto/
│       ├── CreateAppRequest.java     ← NEW
│       ├── CreateVersionRequest.java ← NEW
│       ├── AppResponse.java          ← NEW
│       └── VersionResponse.java      ← NEW
├── storage/
│   └── GcsStorageService.java        ← NEW
├── scanner/                          ← Sprint 4
│   ├── ScanDispatchService.java      ← NEW
│   ├── ScannerCallbackService.java   ← UPDATE (fully wired now)
│   ├── ScannerCallbackController.java ← Sprint 4, no changes
│   ├── HmacVerifier.java             ← Sprint 4, no changes
│   └── dto/
│       └── ScanResultCallback.java   ← Sprint 4, no changes
```

---

## Step 3 — enums

Create `backend/src/main/java/com/appvault/domain/app/AppStatus.java`:

```java
package com.appvault.domain.app;

public enum AppStatus {
    DRAFT, ACTIVE, SUSPENDED, REMOVED
}
```

Create `backend/src/main/java/com/appvault/domain/app/VersionStatus.java`:

```java
package com.appvault.domain.app;

public enum VersionStatus {
    PENDING_SCAN,
    SCAN_COMPLETE,
    APPROVED,
    REJECTED,
    SUPERSEDED
}
```

Create `backend/src/main/java/com/appvault/domain/app/ReleaseTrack.java`:

```java
package com.appvault.domain.app;

public enum ReleaseTrack {
    ALPHA, BETA, PRODUCTION
}
```

Create `backend/src/main/java/com/appvault/domain/app/ContentRating.java`:

```java
package com.appvault.domain.app;

public enum ContentRating {
    EVERYONE, TEEN, MATURE
}
```

---

## Step 4 — App entity

Create `backend/src/main/java/com/appvault/domain/app/App.java`:

```java
package com.appvault.domain.app;

import com.appvault.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "apps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class App {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "developer_id", nullable = false)
    private User developer;

    @Column(nullable = false)
    private String title;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "full_description", columnDefinition = "TEXT")
    private String fullDescription;

    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_rating", columnDefinition = "content_rating")
    @Builder.Default
    private ContentRating contentRating = ContentRating.EVERYONE;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "app_status")
    @Builder.Default
    private AppStatus status = AppStatus.DRAFT;

    @Column(name = "icon_gcs_path")
    private String iconGcsPath;

    @Column(name = "banner_gcs_path")
    private String bannerGcsPath;

    @Column(name = "package_name")
    private String packageName;

    @Column(name = "average_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Column(name = "rating_count")
    @Builder.Default
    private Integer ratingCount = 0;

    @Column(name = "total_downloads")
    @Builder.Default
    private Long totalDownloads = 0L;

    @OneToMany(mappedBy = "app", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AppVersion> versions = new ArrayList<>();

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

---

## Step 5 — AppVersion entity

Create `backend/src/main/java/com/appvault/domain/app/AppVersion.java`:

```java
package com.appvault.domain.app;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "app_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id", nullable = false)
    private App app;

    @Column(name = "version_code", nullable = false)
    private Integer versionCode;

    @Column(name = "version_name", nullable = false)
    private String versionName;

    @Column(columnDefinition = "TEXT")
    private String changelog;

    @Column(name = "apk_gcs_path")
    private String apkGcsPath;

    @Column(name = "apk_size_bytes")
    private Long apkSizeBytes;

    @Column(name = "min_sdk")
    private Integer minSdk;

    @Column(name = "target_sdk")
    private Integer targetSdk;

    @Column(name = "cert_fingerprint")
    private String certFingerprint;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "permissions", columnDefinition = "text[]")
    private List<String> permissions;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "version_status")
    @Builder.Default
    private VersionStatus status = VersionStatus.PENDING_SCAN;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "release_track")
    private ReleaseTrack track;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = false;

    @Column(name = "risk_score")
    private Integer riskScore;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scan_flags", columnDefinition = "text[]")
    private List<String> scanFlags;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "rejection_notes", columnDefinition = "TEXT")
    private String rejectionNotes;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

---

## Step 6 — AppOwnership and ScanJob entities

Create `backend/src/main/java/com/appvault/domain/app/AppOwnership.java`:

```java
package com.appvault.domain.app;

import com.appvault.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_ownership")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppOwnership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_name", nullable = false, unique = true)
    private String packageName;

    @Column(name = "cert_fingerprint", nullable = false)
    private String certFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "developer_id", nullable = false)
    private User developer;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = OffsetDateTime.now();
    }
}
```

Create `backend/src/main/java/com/appvault/domain/app/ScanJob.java`:

```java
package com.appvault.domain.app;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scan_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", nullable = false)
    private AppVersion version;

    @Column(name = "callback_id", nullable = false, unique = true)
    private String callbackId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "DISPATCHED";

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        dispatchedAt = OffsetDateTime.now();
    }
}
```

---

## Step 7 — repositories

Create `backend/src/main/java/com/appvault/domain/app/AppRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppRepository extends JpaRepository<App, UUID> {

    List<App> findByDeveloperIdOrderByCreatedAtDesc(UUID developerId);

    Optional<App> findByIdAndDeveloperId(UUID id, UUID developerId);
}
```

Create `backend/src/main/java/com/appvault/domain/app/AppVersionRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppVersionRepository extends JpaRepository<AppVersion, UUID> {

    List<AppVersion> findByAppIdOrderByVersionCodeDesc(UUID appId);

    @Query("SELECT MAX(v.versionCode) FROM AppVersion v WHERE v.app.id = :appId")
    Optional<Integer> findMaxVersionCodeByAppId(UUID appId);

    Optional<AppVersion> findByIdAndAppId(UUID id, UUID appId);
}
```

Create `backend/src/main/java/com/appvault/domain/app/AppOwnershipRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppOwnershipRepository extends JpaRepository<AppOwnership, UUID> {
    Optional<AppOwnership> findByPackageName(String packageName);
}
```

Create `backend/src/main/java/com/appvault/domain/app/ScanJobRepository.java`:

```java
package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    Optional<ScanJob> findByCallbackId(String callbackId);
}
```

---

## Step 8 — GCS storage service

Add Google Cloud Storage dependency to `pom.xml` inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-storage</artifactId>
    <version>2.36.1</version>
</dependency>
```

Create `backend/src/main/java/com/appvault/storage/GcsStorageService.java`:

```java
package com.appvault.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GcsStorageService {

    @Value("${gcp.storage.bucket}")
    private String bucketName;

    private Storage storage;

    private Storage getStorage() throws IOException {
        if (storage == null) {
            storage = StorageOptions.newBuilder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build()
                    .getService();
        }
        return storage;
    }

    public String uploadApk(String appId, String versionId,
                             MultipartFile file) throws IOException {
        String gcsPath = String.format("apks/%s/%s/app.apk", appId, versionId);
        uploadFile(gcsPath, file.getBytes(), "application/vnd.android.package-archive");
        log.info("APK uploaded to GCS: {}", gcsPath);
        return gcsPath;
    }

    public String uploadIcon(String appId, MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        String gcsPath = String.format("icons/%s/icon.%s", appId, ext);
        uploadFile(gcsPath, file.getBytes(), file.getContentType());
        return gcsPath;
    }

    public String uploadScreenshot(String appId, String filename,
                                    MultipartFile file) throws IOException {
        String gcsPath = String.format("screenshots/%s/%s", appId, filename);
        uploadFile(gcsPath, file.getBytes(), file.getContentType());
        return gcsPath;
    }

    public String generateSignedDownloadUrl(String gcsPath,
                                             int ttlMinutes) throws IOException {
        BlobInfo blobInfo = BlobInfo.newBuilder(
                BlobId.of(bucketName, gcsPath)).build();

        URL signedUrl = getStorage().signUrl(
                blobInfo,
                ttlMinutes,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature()
        );
        return signedUrl.toString();
    }

    public String getGcsUrl(String gcsPath) {
        return "gs://" + bucketName + "/" + gcsPath;
    }

    private void uploadFile(String gcsPath, byte[] bytes,
                             String contentType) throws IOException {
        BlobId blobId = BlobId.of(bucketName, gcsPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();
        getStorage().create(blobInfo, bytes);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "png";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
```

Add to `application.yml`:

```yaml
gcp:
  project-id: ${GCP_PROJECT_ID:local}
  storage:
    bucket: ${GCS_BUCKET:appvault-files}
```

Add `GCS_BUCKET=appvault-files` to `.env` on main-vm.

---

## Step 9 — scan dispatch service

Create `backend/src/main/java/com/appvault/scanner/ScanDispatchService.java`:

```java
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
import java.time.OffsetDateTime;
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
```

---

## Step 10 — DTOs

Create `backend/src/main/java/com/appvault/developer/dto/CreateAppRequest.java`:

```java
package com.appvault.developer.dto;

import com.appvault.domain.app.ContentRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAppRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be under 255 characters")
    private String title;

    @Size(max = 500, message = "Short description must be under 500 characters")
    private String shortDescription;

    private String fullDescription;

    private String category;

    @NotNull(message = "Content rating is required")
    private ContentRating contentRating;
}
```

Create `backend/src/main/java/com/appvault/developer/dto/CreateVersionRequest.java`:

```java
package com.appvault.developer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateVersionRequest {

    @NotBlank(message = "Version name is required")
    private String versionName;

    private String changelog;
}
```

Create `backend/src/main/java/com/appvault/developer/dto/AppResponse.java`:

```java
package com.appvault.developer.dto;

import com.appvault.domain.app.App;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AppResponse {
    private UUID id;
    private String title;
    private String shortDescription;
    private String category;
    private String contentRating;
    private String status;
    private String packageName;
    private Integer versionCount;
    private OffsetDateTime createdAt;

    public static AppResponse from(App app) {
        AppResponse r = new AppResponse();
        r.setId(app.getId());
        r.setTitle(app.getTitle());
        r.setShortDescription(app.getShortDescription());
        r.setCategory(app.getCategory());
        r.setContentRating(app.getContentRating().name());
        r.setStatus(app.getStatus().name());
        r.setPackageName(app.getPackageName());
        r.setVersionCount(app.getVersions().size());
        r.setCreatedAt(app.getCreatedAt());
        return r;
    }
}
```

Create `backend/src/main/java/com/appvault/developer/dto/VersionResponse.java`:

```java
package com.appvault.developer.dto;

import com.appvault.domain.app.AppVersion;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class VersionResponse {
    private UUID id;
    private Integer versionCode;
    private String versionName;
    private String status;
    private String track;
    private Boolean isActive;
    private Integer riskScore;
    private List<String> scanFlags;
    private String rejectionReason;
    private String rejectionNotes;
    private Long apkSizeBytes;
    private Integer minSdk;
    private Integer targetSdk;
    private List<String> permissions;
    private OffsetDateTime createdAt;

    public static VersionResponse from(AppVersion v) {
        VersionResponse r = new VersionResponse();
        r.setId(v.getId());
        r.setVersionCode(v.getVersionCode());
        r.setVersionName(v.getVersionName());
        r.setStatus(v.getStatus().name());
        r.setTrack(v.getTrack() != null ? v.getTrack().name() : null);
        r.setIsActive(v.getIsActive());
        r.setRiskScore(v.getRiskScore());
        r.setScanFlags(v.getScanFlags());
        r.setRejectionReason(v.getRejectionReason());
        r.setRejectionNotes(v.getRejectionNotes());
        r.setApkSizeBytes(v.getApkSizeBytes());
        r.setMinSdk(v.getMinSdk());
        r.setTargetSdk(v.getTargetSdk());
        r.setPermissions(v.getPermissions());
        r.setCreatedAt(v.getCreatedAt());
        return r;
    }
}
```

---

## Step 11 — App service

Create `backend/src/main/java/com/appvault/developer/AppService.java`:

```java
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

        // Call scanner parse endpoint to extract metadata
        // We use the GCS URL — scanner downloads and parses
        // For now store version with PENDING_SCAN status
        // Scanner callback will fill in metadata after scan

        // Get highest existing version code for this app
        Integer maxVersionCode = versionRepository
                .findMaxVersionCodeByAppId(appId)
                .orElse(0);

        // Build version record — metadata filled in by scanner callback
        AppVersion version = AppVersion.builder()
                .app(app)
                .versionCode(maxVersionCode + 1)  // temp — scanner will validate
                .versionName(request.getVersionName())
                .changelog(request.getChangelog())
                .apkGcsPath(apkGcsPath)
                .apkSizeBytes(apkFile.getSize())
                .status(VersionStatus.PENDING_SCAN)
                .build();

        versionRepository.save(version);

        // Update version ID to the one we saved
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
```

---

## Step 12 — App controller

Create `backend/src/main/java/com/appvault/developer/AppController.java`:

```java
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
}
```

---

## Step 13 — wire up ScannerCallbackService

Replace `backend/src/main/java/com/appvault/scanner/ScannerCallbackService.java`:

```java
package com.appvault.scanner;

import com.appvault.domain.app.*;
import com.appvault.scanner.dto.ScanResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;

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
```

---

## Step 14 — update application.yml

Add to `backend/src/main/resources/application.yml`:

```yaml
scanner:
  hmac-secret: ${SCANNER_HMAC_SECRET:local_dev_hmac_secret_32_chars_min}
  callback-url: ${SCANNER_URL:https://scanner.appvault.online/scan}

spring:
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB
```

---

## Step 15 — update .env on main-vm

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP
cd /home/ubuntu/appvault

# Add new variables
echo "GCS_BUCKET=appvault-files" >> .env
echo "SCANNER_URL=https://scanner.appvault.online/scan" >> .env

# Pull HMAC secret (same one scanner uses)
HMAC=$(gcloud secrets versions access latest --secret="scanner-hmac-secret")
echo "SCANNER_HMAC_SECRET=${HMAC}" >> .env

cat .env
```

---

## Step 16 — commit and deploy

```bash
git add .
git commit -m "feat: Sprint 5 — app submission API with GCS upload, ownership binding, scan dispatch"
git push origin main
```

---

## Step 17 — test end to end

```bash
# 1. Register and login as developer
curl -X POST https://appvault.online/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Dev User","email":"dev@appvault.online","password":"password123"}'

# Grab verificationToken from response and verify
curl "https://appvault.online/auth/verify-email?token=TOKEN_HERE"

# Login
curl -X POST https://appvault.online/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@appvault.online","password":"password123"}'

# Save ACCESS_TOKEN from response

# 2. Create app listing
curl -X POST https://appvault.online/developer/apps \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "My Test App",
    "shortDescription": "A test application",
    "category": "TOOLS",
    "contentRating": "EVERYONE"
  }'

# Save APP_ID from response

# 3. Upload APK version
curl -X POST https://appvault.online/developer/apps/APP_ID/versions \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -F "versionName=1.0.0" \
  -F "changelog=First release" \
  -F "apkFile=@/path/to/your/app.apk"

# Expected: 201 with version status PENDING_SCAN

# 4. List versions — check status
curl https://appvault.online/developer/apps/APP_ID/versions \
  -H "Authorization: Bearer ACCESS_TOKEN"

# Wait ~30 seconds for scanner to process, then check again
# Status should change from PENDING_SCAN to SCAN_COMPLETE

# 5. List all apps
curl https://appvault.online/developer/apps \
  -H "Authorization: Bearer ACCESS_TOKEN"

# 6. Try without token — should return 403
curl -X POST https://appvault.online/developer/apps \
  -H "Content-Type: application/json" \
  -d '{"title":"Hack","contentRating":"EVERYONE"}'
```

---

## Sprint 5 definition of done checklist

```
[ ] POST /developer/apps creates app and returns 201
[ ] GET  /developer/apps returns only the authenticated developer's apps
[ ] POST /developer/apps/{id}/versions uploads APK to GCS successfully
[ ] Version created with status PENDING_SCAN
[ ] Scan job dispatched to scanner VM (check scanner logs)
[ ] Scanner processes APK and sends callback to main platform
[ ] Version status updates to SCAN_COMPLETE after callback
[ ] Package name and cert fingerprint stored on version
[ ] Ownership record created in app_ownership table
[ ] Unauthenticated request to /developer/* returns 403
[ ] App deployed successfully via GitHub Actions
```