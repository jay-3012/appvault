package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppRepository extends JpaRepository<App, UUID> {

    List<App> findByDeveloperIdOrderByCreatedAtDesc(UUID developerId);

    Optional<App> findByIdAndDeveloperId(UUID id, UUID developerId);
}
