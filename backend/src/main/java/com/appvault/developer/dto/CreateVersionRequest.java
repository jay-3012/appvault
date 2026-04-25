package com.appvault.developer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateVersionRequest {

    @NotBlank(message = "Version name is required")
    private String versionName;

    private String changelog;
}
