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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
}
