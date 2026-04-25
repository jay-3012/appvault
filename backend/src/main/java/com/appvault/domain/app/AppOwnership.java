package com.appvault.domain.app;

import com.appvault.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_ownership")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppOwnership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_name", nullable = false, unique = true)
    private String packageName;

    @Column(name = "cert_fingerprint", nullable = false)
    private String certFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "developer_id", nullable = false)
    private User developer;

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = OffsetDateTime.now();
    }
}
