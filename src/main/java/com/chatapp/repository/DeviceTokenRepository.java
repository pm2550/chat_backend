package com.chatapp.repository;

import com.chatapp.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    List<DeviceToken> findByUserIdAndIsActiveTrue(Long userId);
    Optional<DeviceToken> findByToken(String token);
    void deleteByToken(String token);
    void deleteByUserId(Long userId);
    List<DeviceToken> findByUserIdAndPlatformAndIsActiveTrue(Long userId, DeviceToken.Platform platform);
}
