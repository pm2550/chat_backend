package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.BotWebhookSubscription;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.BotWebhookSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotWebhookServiceTest {

    @Mock private BotWebhookSubscriptionRepository subscriptionRepository;
    @Mock private BotConfigRepository botConfigRepository;

    // Real collaborators (no external deps): SSRF policy + crypto + json.
    private final OutboundUrlPolicy outboundUrlPolicy = new OutboundUrlPolicy();
    private final CredentialCryptoService crypto =
            new CredentialCryptoService("test-master-key-material-32-bytes-long");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BotWebhookService service;
    private User owner;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        service = new BotWebhookService(subscriptionRepository, botConfigRepository,
                outboundUrlPolicy, crypto, objectMapper);
        owner = new User();
        owner.setId(1L);
        bot = new BotConfig();
        bot.setId(5L);
        bot.setCreatedBy(owner);
    }

    @Test
    void registerRejectsInternalCallbackUrlAsSsrf() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(5L, 1L, "http://10.0.0.5/hook", "sek", "message", null));
    }

    @Test
    void registerAcceptsPublicHttpsCallback() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        when(subscriptionRepository.save(any(BotWebhookSubscription.class))).thenAnswer(inv -> {
            BotWebhookSubscription s = inv.getArgument(0);
            s.setId(70L);
            return s;
        });
        // literal public IP avoids DNS in tests
        BotWebhookService.WebhookView view =
                service.register(5L, 1L, "https://8.8.8.8/hook", "shh", "message", null);
        assertEquals("https://8.8.8.8/hook", view.callbackUrl());
        assertTrue(view.hasSecret());
    }

    @Test
    void registerRejectsNonOwner() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.register(5L, 999L, "https://8.8.8.8/hook", null, null, null));
    }

    @Test
    void dispatchReturnsTrueWhenSubscribed() {
        BotWebhookSubscription sub = new BotWebhookSubscription();
        sub.setId(70L);
        sub.setBotConfig(bot);
        sub.setChatRoomId(null); // all rooms
        sub.setCallbackUrl("https://8.8.8.8/hook");
        sub.setIsActive(true);
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(sub));
        // async deliver re-fetches by id; unstubbed findById returns Optional.empty() by
        // default, so deliver no-ops (no real network) — no explicit stub needed.

        assertTrue(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }

    @Test
    void dispatchReturnsFalseWhenNoSubscriptions() {
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of());
        assertFalse(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }
}
