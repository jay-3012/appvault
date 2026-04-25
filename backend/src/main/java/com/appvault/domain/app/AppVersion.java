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
