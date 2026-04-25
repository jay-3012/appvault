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
