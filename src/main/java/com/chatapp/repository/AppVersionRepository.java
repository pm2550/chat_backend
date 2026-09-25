package com.chatapp.repository;

import com.chatapp.entity.AppVersion;
import com.chatapp.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    Optional<AppVersion> findFirstByPlatformAndIsActiveTrueOrderByVersionCodeDesc(
            DeviceToken.Platform platform);

    Optional<AppVersion> findByPlatformAndVersionCode(DeviceToken.Platform platform, Integer versionCode);

    Optional<AppVersion> findByPlatformAndVersionCodeAndAbi(
            DeviceToken.Platform platform, Integer versionCode, String abi);

    /** 某平台下这些 ABI（含整包 ""）里仍在发布的版本，新的在前。 */
    List<AppVersion> findByPlatformAndAbiInAndIsActiveTrueOrderByVersionCodeDesc(
            DeviceToken.Platform platform, Collection<String> abis);

    List<AppVersion> findByPlatformOrderByVersionCodeDesc(DeviceToken.Platform platform);
}
