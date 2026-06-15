package com.chatapp.service;

import com.chatapp.config.WebPushProperties;
import com.chatapp.dto.WebPushDto;
import com.chatapp.entity.DeviceToken;
import com.chatapp.entity.WebPushSubscription;
import com.chatapp.repository.DeviceTokenRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.WebPushSubscriptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final WebPushSubscriptionRepository webPushSubscriptionRepository;
    private final UserRepository userRepository;
    private final WebPushProperties webPushProperties;
    private final ObjectMapper objectMapper;

    public WebPushDto.VapidPublicKeyResponse getWebPushPublicKey() {
        return new WebPushDto.VapidPublicKeyResponse(
                webPushProperties.getPublicKey(),
                webPushProperties.isConfigured()
        );
    }

    @Transactional
    public void subscribeWebPush(Long userId, WebPushDto.SubscribeRequest request) {
        String endpointHash = endpointHash(request.getEndpoint());
        WebPushSubscription subscription = webPushSubscriptionRepository
                .findByEndpointHash(endpointHash)
                .orElseGet(WebPushSubscription::new);
        subscription.setUser(userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在")));
        subscription.setEndpoint(request.getEndpoint());
        subscription.setEndpointHash(endpointHash);
        subscription.setP256dh(request.getKeys().getP256dh());
        subscription.setAuth(request.getKeys().getAuth());
        subscription.setUserAgent(trim(request.getUserAgent(), 512));
        subscription.setIsActive(true);
        subscription.setLastError(null);
        subscription.setLastErrorAt(null);
        webPushSubscriptionRepository.save(subscription);
        log.info("用户 {} 注册了 Web Push 订阅", userId);
    }

    @Transactional
    public void unsubscribeWebPush(Long userId, WebPushDto.UnsubscribeRequest request) {
        String endpointHash = endpointHash(request.getEndpoint());
        webPushSubscriptionRepository.findByUserIdAndEndpointHash(userId, endpointHash)
                .ifPresent(subscription -> {
                    subscription.setIsActive(false);
                    webPushSubscriptionRepository.save(subscription);
                });
    }

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

    @Transactional
    public void sendPushNotification(Long userId, String title, String body, String data) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
        boolean deliveredAny = false;

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
                deliveredAny = true;
            } catch (Exception e) {
                log.error("推送通知失败 (用户: {}, 平台: {}): {}", userId, token.getPlatform(), e.getMessage());
            }
        }

        deliveredAny = sendStoredWebPush(userId, title, body, data) || deliveredAny;
        if (!deliveredAny) {
            log.debug("用户 {} 没有活跃的设备令牌或 Web Push 订阅", userId);
        }
    }

    public void sendPushToMultipleUsers(List<Long> userIds, String title, String body, String data) {
        for (Long userId : userIds) {
            sendPushNotification(userId, title, body, data);
        }
    }

    // Provider adapters log delivery intents unless a deployment wires external SDK credentials.
    private void sendFCM(String token, String title, String body, String data) {
        log.info("FCM push: {} - {}", title, body);
    }

    private void sendAPNs(String token, String title, String body, String data) {
        log.info("APNs push: {} - {}", title, body);
    }

    private void sendWebPush(String token, String title, String body, String data) {
        log.info("Web push: {} - {}", title, body);
    }

    private boolean sendStoredWebPush(Long userId, String title, String body, String data) {
        List<WebPushSubscription> subscriptions =
                webPushSubscriptionRepository.findByUserIdAndIsActiveTrue(userId);
        if (subscriptions.isEmpty()) {
            return false;
        }
        if (!webPushProperties.isConfigured()) {
            log.warn("Web Push skipped for user {}: VAPID keys are not configured", userId);
            return false;
        }

        String payload = webPushPayload(title, body, data);
        boolean attempted = false;
        for (WebPushSubscription subscription : subscriptions) {
            try {
                attempted = true;
                Notification notification = new Notification(
                        subscription.getEndpoint(),
                        subscription.getP256dh(),
                        subscription.getAuth(),
                        payload
                );
                PushService pushService = new PushService(
                        webPushProperties.getPublicKey(),
                        webPushProperties.getPrivateKey(),
                        webPushProperties.getSubject()
                );
                HttpResponse response = pushService.send(notification);
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    deactivateInvalidSubscription(subscription, status);
                } else if (status >= 300) {
                    markSubscriptionError(subscription, "HTTP " + status);
                } else {
                    log.info("Web Push sent to user {} subscription {}", userId, subscription.getId());
                }
            } catch (Exception e) {
                markSubscriptionError(subscription, trim(e.getMessage(), 512));
                log.warn("Web Push failed for user {} subscription {}: {}",
                        userId, subscription.getId(), e.getMessage());
            }
        }
        return attempted;
    }

    private String webPushPayload(String title, String body, String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("icon", "/icons/Icon-192.png");
        payload.put("badge", "/icons/Icon-192.png");
        Map<String, Object> parsedData = parseData(data);
        payload.put("data", parsedData);
        payload.put("url", notificationUrl(parsedData));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"title\":\"PM chat\",\"body\":\"你有一条新消息\",\"url\":\"/\"}";
        }
    }

    private Map<String, Object> parseData(String data) {
        if (data == null || data.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String notificationUrl(Map<String, Object> data) {
        Object chatRoomId = data.get("chatRoomId");
        if (chatRoomId != null && !chatRoomId.toString().isBlank()) {
            return "/#/chat/" + chatRoomId;
        }
        return "/#/home/chats";
    }

    private void deactivateInvalidSubscription(WebPushSubscription subscription, int status) {
        subscription.setIsActive(false);
        subscription.setLastError("HTTP " + status);
        subscription.setLastErrorAt(LocalDateTime.now());
        webPushSubscriptionRepository.save(subscription);
        log.info("Deactivated stale Web Push subscription {}", subscription.getId());
    }

    private void markSubscriptionError(WebPushSubscription subscription, String error) {
        subscription.setLastError(error);
        subscription.setLastErrorAt(LocalDateTime.now());
        webPushSubscriptionRepository.save(subscription);
    }

    private void sendWNS(String token, String title, String body, String data) {
        log.info("WNS push: {} - {}", title, body);
    }

    private void sendHMSPush(String token, String title, String body, String data) {
        log.info("HMS push: {} - {}", title, body);
    }

    private String endpointHash(String endpoint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Web Push endpoint hash", e);
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
