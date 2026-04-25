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
