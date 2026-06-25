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
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final WebPushSubscriptionRepository webPushSubscriptionRepository;
    private final UserRepository userRepository;
    private final WebPushProperties webPushProperties;
    private final ObjectMapper objectMapper;

    @Value("${push.fcm.service-account-json:}")
    private String fcmServiceAccountJson;

    @Value("${push.fcm.service-account-base64:}")
    private String fcmServiceAccountBase64;

    @Value("${push.fcm.enabled:true}")
    private boolean fcmEnabled;

    private volatile FirebaseMessaging firebaseMessaging;

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

    private void sendFCM(String token, String title, String body, String data) throws Exception {
        FirebaseMessaging messaging = getFirebaseMessaging();
        if (messaging == null) {
            log.warn("FCM push skipped: Firebase credentials are not configured");
            return;
        }

        Message message = Message.builder()
                .setToken(token)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putAllData(firebaseDataPayload(data))
                .build();
        String response = messaging.send(message);
        log.info("FCM push sent: {}", response);
    }

    private void sendAPNs(String token, String title, String body, String data) throws Exception {
        // iOS app tokens produced by firebase_messaging are FCM registration tokens too.
        sendFCM(token, title, body, data);
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

    private Map<String, String> firebaseDataPayload(String data) {
        Map<String, Object> parsedData = parseData(data);
        Map<String, String> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsedData.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                payload.put(entry.getKey(), entry.getValue().toString());
            }
        }
        payload.putIfAbsent("url", notificationUrl(parsedData));
        return payload;
    }

    private FirebaseMessaging getFirebaseMessaging() {
        if (!fcmEnabled) {
            return null;
        }
        FirebaseMessaging existing = firebaseMessaging;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (firebaseMessaging != null) {
                return firebaseMessaging;
            }
            String credentialsJson = fcmCredentialsJson();
            if (credentialsJson.isBlank()) {
                return null;
            }
            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))
                );
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();
                FirebaseApp app;
                try {
                    app = FirebaseApp.getInstance("pmchat-fcm");
                } catch (IllegalStateException ignored) {
                    app = FirebaseApp.initializeApp(options, "pmchat-fcm");
                }
                firebaseMessaging = FirebaseMessaging.getInstance(app);
                return firebaseMessaging;
            } catch (Exception e) {
                log.error("FCM initialization failed: {}", e.getMessage());
                return null;
            }
        }
    }

    private String fcmCredentialsJson() {
        if (fcmServiceAccountJson != null && !fcmServiceAccountJson.isBlank()) {
            return fcmServiceAccountJson;
        }
        if (fcmServiceAccountBase64 != null && !fcmServiceAccountBase64.isBlank()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(fcmServiceAccountBase64);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                log.error("FCM service account base64 is invalid: {}", e.getMessage());
            }
        }
        return "";
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
