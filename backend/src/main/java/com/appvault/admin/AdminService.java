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
