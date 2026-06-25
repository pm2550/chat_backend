package com.chatapp.repository;

import com.chatapp.entity.AppVersion;
import com.chatapp.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    Optional<AppVersion> findFirstByPlatformAndIsActiveTrueOrderByVersionCodeDesc(
            DeviceToken.Platform platform);

    Optional<AppVersion> findByPlatformAndVersionCode(DeviceToken.Platform platform, Integer versionCode);

    List<AppVersion> findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform platform);
}
