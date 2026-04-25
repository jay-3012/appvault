package com.appvault.developer.dto;

import com.appvault.domain.app.ContentRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAppRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be under 255 characters")
    private String title;

    @Size(max = 500, message = "Short description must be under 500 characters")
    private String shortDescription;

    private String fullDescription;

    private String category;

    @NotNull(message = "Content rating is required")
    private ContentRating contentRating;
}
