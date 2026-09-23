package com.chatapp.service;

import com.chatapp.config.WebPushProperties;
import com.chatapp.dto.WebPushDto;
import com.chatapp.entity.User;
import com.chatapp.entity.WebPushSubscription;
import com.chatapp.repository.DeviceTokenRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.WebPushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private WebPushSubscriptionRepository webPushSubscriptionRepository;
    @Mock private UserRepository userRepository;

    private WebPushProperties properties;
    private PushNotificationService service;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new WebPushProperties();
        properties.setPublicKey("public");
        properties.setPrivateKey("private");
        properties.setSubject("mailto:test@example.com");
        service = new PushNotificationService(
                deviceTokenRepository,
                webPushSubscriptionRepository,
                userRepository,
                properties,
                new ObjectMapper());
        user = new User();
        user.setId(7L);
        user.setUsername("alice");
    }

    @Test
    void exposesVapidPublicKeyWithoutPrivateKey() {
        WebPushDto.VapidPublicKeyResponse response = service.getWebPushPublicKey();

        assertEquals("public", response.getPublicKey());
        assertTrue(response.isConfigured());
    }

    @Test
    void subscribeWebPushUpsertsEndpointAndStoresKeys() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(webPushSubscriptionRepository.findByEndpointHash(any())).thenReturn(Optional.empty());
        WebPushDto.SubscribeRequest request = new WebPushDto.SubscribeRequest(
                "https://push.example/subscription/abc",
                new WebPushDto.Keys("p256dh", "auth"),
                "Unit Browser");

        service.subscribeWebPush(7L, request);

        ArgumentCaptor<WebPushSubscription> captor =
                ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(webPushSubscriptionRepository).save(captor.capture());
        WebPushSubscription saved = captor.getValue();
        assertEquals(user, saved.getUser());
        assertEquals(request.getEndpoint(), saved.getEndpoint());
        assertEquals("p256dh", saved.getP256dh());
        assertEquals("auth", saved.getAuth());
        assertEquals("Unit Browser", saved.getUserAgent());
        assertEquals(64, saved.getEndpointHash().length());
        assertTrue(saved.getIsActive());
    }

    @Test
    void unsubscribeWebPushMarksOnlyCurrentUserEndpointInactive() {
        WebPushSubscription subscription = new WebPushSubscription();
        subscription.setIsActive(true);
        when(webPushSubscriptionRepository.findByUserIdAndEndpointHash(any(), any()))
                .thenReturn(Optional.of(subscription));

        service.unsubscribeWebPush(
                7L,
                new WebPushDto.UnsubscribeRequest("https://push.example/subscription/abc"));

        assertFalse(subscription.getIsActive());
        verify(webPushSubscriptionRepository).save(subscription);
    }

    @Test
    void sendPushNotificationDoesNotFailWhenWebPushKeysMissing() {
        properties.setPrivateKey("");
        WebPushSubscription subscription = new WebPushSubscription();
        subscription.setId(3L);
        subscription.setIsActive(true);
        when(deviceTokenRepository.findByUserIdAndIsActiveTrue(7L)).thenReturn(List.of());
        when(webPushSubscriptionRepository.findByUserIdAndIsActiveTrue(7L))
                .thenReturn(List.of(subscription));

        service.sendPushNotification(7L, "Title", "Body", "{}");

        verify(webPushSubscriptionRepository).findByUserIdAndIsActiveTrue(7L);
    }

    @Test
    void sendPushNotificationActuallyEncryptsAndDeliversWebPush() throws Exception {
        // 生产上一直报 "no such provider: BC"，一条网页推送都没发出去；这里真的走一遍加密发送。
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair vapid = generator.generateKeyPair();
        KeyPair browser = generator.generateKeyPair();
        properties.setPublicKey(base64Url(uncompressed((ECPublicKey) vapid.getPublic())));
        properties.setPrivateKey(base64Url(privateScalar((ECPrivateKey) vapid.getPrivate())));
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(Map.of(
                    "encoding", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Encoding")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "bodyLength", String.valueOf(body.length)));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        try {
            WebPushSubscription subscription = new WebPushSubscription();
            subscription.setId(9L);
            subscription.setIsActive(true);
            subscription.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc");
            subscription.setP256dh(base64Url(uncompressed((ECPublicKey) browser.getPublic())));
            subscription.setAuth(base64Url(authSecret));
            when(deviceTokenRepository.findByUserIdAndIsActiveTrue(7L)).thenReturn(List.of());
            when(webPushSubscriptionRepository.findByUserIdAndIsActiveTrue(7L))
                    .thenReturn(List.of(subscription));

            service.sendPushNotification(7L, "新消息", "你好", "{}");

            assertEquals(1, received.size());
            assertEquals("aes128gcm", received.get(0).get("encoding"));
            assertTrue(received.get(0).get("authorization").startsWith("vapid"));
            assertTrue(Integer.parseInt(received.get(0).get("bodyLength")) > 0);
            // 发送成功就不应把订阅记成失败
            verify(webPushSubscriptionRepository, never()).save(any());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] uncompressed(ECPublicKey key) {
        byte[] x = fixed32(key.getW().getAffineX().toByteArray());
        byte[] y = fixed32(key.getW().getAffineY().toByteArray());
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] privateScalar(ECPrivateKey key) {
        return fixed32(key.getS().toByteArray());
    }

    private static byte[] fixed32(byte[] value) {
        byte[] out = new byte[32];
        int copy = Math.min(32, value.length);
        System.arraycopy(value, value.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
