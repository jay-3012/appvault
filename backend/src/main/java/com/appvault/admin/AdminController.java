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
