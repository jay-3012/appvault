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
