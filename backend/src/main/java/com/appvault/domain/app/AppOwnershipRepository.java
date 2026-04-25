package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppOwnershipRepository extends JpaRepository<AppOwnership, UUID> {
    Optional<AppOwnership> findByPackageName(String packageName);
}
