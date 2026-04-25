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
