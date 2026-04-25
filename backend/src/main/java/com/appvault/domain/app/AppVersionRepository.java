package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppVersionRepository extends JpaRepository<AppVersion, UUID> {

    List<AppVersion> findByAppIdOrderByVersionCodeDesc(UUID appId);

    @Query("SELECT MAX(v.versionCode) FROM AppVersion v WHERE v.app.id = :appId")
    Optional<Integer> findMaxVersionCodeByAppId(UUID appId);

    Optional<AppVersion> findByIdAndAppId(UUID id, UUID appId);
}
