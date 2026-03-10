package com.chatapp.service;

import com.chatapp.entity.DeviceToken;
import com.chatapp.repository.DeviceTokenRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public void registerDeviceToken(Long userId, String token, DeviceToken.Platform platform, String deviceInfo) {
        // Deactivate any existing token with same value
        deviceTokenRepository.findByToken(token).ifPresent(existing -> {
            existing.setIsActive(false);
            deviceTokenRepository.save(existing);
        });

        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setUser(userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在")));
        deviceToken.setToken(token);
        deviceToken.setPlatform(platform);
        deviceToken.setDeviceInfo(deviceInfo);
        deviceToken.setIsActive(true);

        deviceTokenRepository.save(deviceToken);
        log.info("用户 {} 注册了设备令牌 ({})", userId, platform);
    }

    @Transactional
    public void unregisterDeviceToken(String token) {
        deviceTokenRepository.findByToken(token).ifPresent(dt -> {
            dt.setIsActive(false);
            deviceTokenRepository.save(dt);
        });
    }

    public void sendPushNotification(Long userId, String title, String body, String data) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
        if (tokens.isEmpty()) {
            log.debug("用户 {} 没有活跃的设备令牌", userId);
            return;
        }

        for (DeviceToken token : tokens) {
            try {
                switch (token.getPlatform()) {
                    case ANDROID -> sendFCM(token.getToken(), title, body, data);
                    case IOS -> sendAPNs(token.getToken(), title, body, data);
                    case WEB -> sendWebPush(token.getToken(), title, body, data);
                    case WINDOWS -> sendWNS(token.getToken(), title, body, data);
                    case HARMONY -> sendHMSPush(token.getToken(), title, body, data);
                    default -> log.warn("未支持的推送平台: {}", token.getPlatform());
                }
            } catch (Exception e) {
                log.error("推送通知失败 (用户: {}, 平台: {}): {}", userId, token.getPlatform(), e.getMessage());
            }
        }
    }

    public void sendPushToMultipleUsers(List<Long> userIds, String title, String body, String data) {
        for (Long userId : userIds) {
            sendPushNotification(userId, title, body, data);
        }
    }

    // Platform-specific push implementations (stubs - to be implemented with actual SDKs)
    private void sendFCM(String token, String title, String body, String data) {
        log.info("FCM push: {} - {}", title, body);
        // TODO: Integrate Firebase Admin SDK
    }

    private void sendAPNs(String token, String title, String body, String data) {
        log.info("APNs push: {} - {}", title, body);
        // TODO: Integrate Apple Push Notification service
    }

    private void sendWebPush(String token, String title, String body, String data) {
        log.info("Web push: {} - {}", title, body);
        // TODO: Integrate Web Push API
    }

    private void sendWNS(String token, String title, String body, String data) {
        log.info("WNS push: {} - {}", title, body);
        // TODO: Integrate Windows Notification Service
    }

    private void sendHMSPush(String token, String title, String body, String data) {
        log.info("HMS push: {} - {}", title, body);
        // TODO: Integrate Huawei Mobile Services Push Kit
    }
}
