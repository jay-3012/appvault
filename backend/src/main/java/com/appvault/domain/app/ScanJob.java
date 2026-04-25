package com.appvault.domain.app;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scan_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", nullable = false)
    private AppVersion version;

    @Column(name = "callback_id", nullable = false, unique = true)
    private String callbackId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "DISPATCHED";

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        dispatchedAt = OffsetDateTime.now();
    }
}
