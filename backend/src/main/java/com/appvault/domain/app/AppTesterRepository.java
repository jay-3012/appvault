package com.appvault.domain.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppTesterRepository extends JpaRepository<AppTester, UUID> {
    List<AppTester> findByAppId(UUID appId);
    List<AppTester> findByAppIdAndTrack(UUID appId, ReleaseTrack track);
    boolean existsByAppIdAndEmailAndTrack(UUID appId, String email, ReleaseTrack track);
    void deleteByAppIdAndEmail(UUID appId, String email);
}
