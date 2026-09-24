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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 保存 webhook 是"每个 bot 每个作用域一条"的更新，且按 eventTypes 过滤推送。 */
@ExtendWith(MockitoExtension.class)
class BotWebhookServiceUpsertTest {

    @Mock private BotWebhookSubscriptionRepository subscriptionRepository;
    @Mock private BotConfigRepository botConfigRepository;

    private final CredentialCryptoService crypto =
            new CredentialCryptoService("test-master-key-material-32-bytes-long");
    private BotWebhookService service;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        service = new BotWebhookService(subscriptionRepository, botConfigRepository,
                new OutboundUrlPolicy(), crypto, new ObjectMapper());
        User owner = new User();
        owner.setId(1L);
        bot = new BotConfig();
        bot.setId(5L);
        bot.setCreatedBy(owner);
    }

    @Test
    void savingNewUrlUpdatesExistingSubscriptionInsteadOfAddingAnother() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        BotWebhookSubscription existing = sub(10L, null, "https://8.8.8.8/old", "message.created");
        existing.setSecretEncrypted(crypto.encrypt("old-secret"));
        existing.setIsActive(false);
        existing.setConsecutiveFailures(20);
        when(subscriptionRepository.findByBotConfigId(5L)).thenReturn(List.of(existing));
        when(subscriptionRepository.save(any(BotWebhookSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        BotWebhookService.WebhookView view =
                service.register(5L, 1L, "https://8.8.4.4/new", "", "message", null);

        ArgumentCaptor<BotWebhookSubscription> saved = ArgumentCaptor.forClass(BotWebhookSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertSame(existing, saved.getValue());
        assertEquals(10L, view.id());
        assertEquals("https://8.8.4.4/new", existing.getCallbackUrl());
        assertEquals("old-secret", crypto.decryptPossiblyLegacy(existing.getSecretEncrypted()),
                "留空 secret 表示沿用原 secret");
        assertTrue(existing.getIsActive());
        assertEquals(0, existing.getConsecutiveFailures());
    }

    @Test
    void duplicateSubscriptionsFromOldSavesAreCollapsed() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        BotWebhookSubscription older = sub(10L, null, "https://8.8.8.8/a", "message.created");
        BotWebhookSubscription newer = sub(11L, null, "https://8.8.8.8/b", "message.created");
        BotWebhookSubscription otherRoom = sub(12L, 100L, "https://8.8.8.8/room", "message");
        when(subscriptionRepository.findByBotConfigId(5L))
                .thenReturn(new ArrayList<>(List.of(older, newer, otherRoom)));
        when(subscriptionRepository.save(any(BotWebhookSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        BotWebhookService.WebhookView view =
                service.register(5L, 1L, "https://8.8.4.4/c", "s", null, null);

        assertEquals(11L, view.id());
        verify(subscriptionRepository).deleteAll(List.of(older));
        assertEquals("https://8.8.8.8/room", otherRoom.getCallbackUrl());
    }

    @Test
    void unknownEventTypeIsRejected() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.register(5L, 1L, "https://8.8.4.4/c", null, "member.joined", null));
        assertTrue(error.getMessage().contains("member.joined"));
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void subscriptionWithoutMessageEventIsNotDispatched() {
        BotWebhookSubscription other = sub(10L, null, "https://8.8.8.8/hook", "reaction");
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(other));

        assertFalse(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }

    @Test
    void legacyMessageCreatedAliasStillReceivesMessages() {
        BotWebhookSubscription legacy = sub(10L, null, "https://8.8.8.8/hook", "message.created");
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(legacy));

        assertTrue(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }

    private BotWebhookSubscription sub(Long id, Long roomId, String url, String eventTypes) {
        BotWebhookSubscription sub = new BotWebhookSubscription();
        sub.setId(id);
        sub.setBotConfig(bot);
        sub.setChatRoomId(roomId);
        sub.setCallbackUrl(url);
        sub.setEventTypes(eventTypes);
        sub.setIsActive(true);
        return sub;
    }
}
