package com.appvault.developer.dto;

import com.appvault.domain.app.App;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AppResponse {
    private UUID id;
    private String title;
    private String shortDescription;
    private String category;
    private String contentRating;
    private String status;
    private String packageName;
    private Integer versionCount;
    private OffsetDateTime createdAt;

    public static AppResponse from(App app) {
        AppResponse r = new AppResponse();
        r.setId(app.getId());
        r.setTitle(app.getTitle());
        r.setShortDescription(app.getShortDescription());
        r.setCategory(app.getCategory());
        r.setContentRating(app.getContentRating().name());
        r.setStatus(app.getStatus().name());
        r.setPackageName(app.getPackageName());
        r.setVersionCount(app.getVersions().size());
        r.setCreatedAt(app.getCreatedAt());
        return r;
    }
}
