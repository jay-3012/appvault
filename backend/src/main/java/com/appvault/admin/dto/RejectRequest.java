package com.appvault.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectRequest {

    @NotBlank(message = "Rejection reason is required")
    private String reason;  // MALWARE_DETECTED | POLICY_VIOLATION | etc.

    private String notes;
}
