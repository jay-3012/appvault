package com.appvault.domain.app;

import com.appvault.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "apps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class App {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "developer_id", nullable = false)
    private User developer;

    @Column(nullable = false)
    private String title;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "full_description", columnDefinition = "TEXT")
    private String fullDescription;

    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_rating", columnDefinition = "content_rating")
    @Builder.Default
    private ContentRating contentRating = ContentRating.EVERYONE;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "app_status")
    @Builder.Default
    private AppStatus status = AppStatus.DRAFT;

    @Column(name = "icon_gcs_path")
    private String iconGcsPath;

    @Column(name = "banner_gcs_path")
    private String bannerGcsPath;

    @Column(name = "package_name")
    private String packageName;

    @Column(name = "average_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Column(name = "rating_count")
    @Builder.Default
    private Integer ratingCount = 0;

    @Column(name = "total_downloads")
    @Builder.Default
    private Long totalDownloads = 0L;

    @OneToMany(mappedBy = "app", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AppVersion> versions = new ArrayList<>();

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
