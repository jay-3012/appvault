package com.appvault.domain.app;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_testers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppTester {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id", nullable = false)
    private App app;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "release_track")
    @Builder.Default
    private ReleaseTrack track = ReleaseTrack.ALPHA;

    @Column(name = "added_at")
    private OffsetDateTime addedAt;

    @PrePersist
    protected void onCreate() {
        addedAt = OffsetDateTime.now();
    }
}
