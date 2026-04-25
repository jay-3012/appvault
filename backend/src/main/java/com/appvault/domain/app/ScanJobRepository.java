package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    Optional<ScanJob> findByCallbackId(String callbackId);
}
