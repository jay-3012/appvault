package com.appvault.admin.dto;

import com.appvault.domain.app.ContentRating;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ContentRatingOverride {

    @NotNull(message = "Content rating is required")
    private ContentRating contentRating;

    private String reason;
}
