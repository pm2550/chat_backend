package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.BotWebhookSubscription;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.BotWebhookSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Outbound bot webhooks (Phase 4 / F1 Slice 2). Owners register a callback URL; when a
 * matching room event fires, it is HMAC-signed and POSTed to that URL (the external
 * bot then replies via the inbound gateway). The presence of an active subscription is
 * what makes a bot "externally driven" — no BRIDGE enum value is needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotWebhookService {

    private static final int MAX_CONSECUTIVE_FAILURES = 20;
    private static final MediaType JSON = MediaType.parse("application/json");

    private final BotWebhookSubscriptionRepository subscriptionRepository;
    private final BotConfigRepository botConfigRepository;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final CredentialCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bot-webhook-dispatch");
        t.setDaemon(true);
        return t;
    });

    // ---- CRUD (owner-gated) ----

    @Transactional
    public WebhookView register(Long botId, Long ownerId, String callbackUrl, String secret,
                                String eventTypes, Long chatRoomId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("机器人不存在"));
        assertOwner(bot, ownerId);
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("callbackUrl 不能为空");
        }
        // SSRF guard: a user-supplied callback may not target internal hosts (except the curated allowlist).
        outboundUrlPolicy.assertAllowed(callbackUrl.trim(), OutboundUrlPolicy.Caller.USER_SUPPLIED);

        BotWebhookSubscription sub = new BotWebhookSubscription();
        sub.setBotConfig(bot);
        sub.setCallbackUrl(callbackUrl.trim());
        sub.setSecretEncrypted(secret != null && !secret.isBlank() ? cryptoService.encrypt(secret) : null);
        sub.setEventTypes(eventTypes != null && !eventTypes.isBlank() ? eventTypes.trim() : "message");
        sub.setChatRoomId(chatRoomId);
        sub.setCreatedBy(ownerId);
        sub.setIsActive(true);
        return toView(subscriptionRepository.save(sub));
    }

    @Transactional(readOnly = true)
    public List<WebhookView> list(Long botId, Long ownerId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("机器人不存在"));
        assertOwner(bot, ownerId);
        return subscriptionRepository.findByBotConfigId(botId).stream().map(this::toView).toList();
    }

    @Transactional
    public void delete(Long subscriptionId, Long ownerId) {
        BotWebhookSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在"));
        assertOwner(sub.getBotConfig(), ownerId);
        subscriptionRepository.delete(sub);
    }

    // ---- dispatch ----

    /**
     * If {@code bot} has active webhook subscriptions matching the room, sign + POST the
     * event asynchronously and return true (caller should NOT then call the LLM).
     */
    public boolean dispatchIfSubscribed(BotConfig bot, Long roomId, String content, Long senderId) {
        List<BotWebhookSubscription> subs = subscriptionRepository.findByBotConfigIdAndIsActiveTrue(bot.getId());
        boolean dispatched = false;
        for (BotWebhookSubscription sub : subs) {
            if (sub.getChatRoomId() != null && !sub.getChatRoomId().equals(roomId)) {
                continue;
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("event", "message");
            payload.put("botId", bot.getId());
            payload.put("roomId", roomId);
            payload.put("senderId", senderId);
            payload.put("content", content);
            payload.put("ts", System.currentTimeMillis() / 1000);
            String body = payload.toString();
            Long subId = sub.getId();
            executor.submit(() -> deliver(subId, body));
            dispatched = true;
        }
        return dispatched;
    }

    private void deliver(Long subscriptionId, String body) {
        BotWebhookSubscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || !Boolean.TRUE.equals(sub.getIsActive())) {
            return;
        }
        try {
            URI uri = outboundUrlPolicy.assertAllowed(sub.getCallbackUrl(), OutboundUrlPolicy.Caller.USER_SUPPLIED);
            long ts = System.currentTimeMillis() / 1000;
            Request.Builder builder = new Request.Builder()
                    .url(uri.toString())
                    .header("Content-Type", "application/json")
                    .header("X-PM-Timestamp", String.valueOf(ts))
                    .header("X-PM-Event", "message")
                    .post(RequestBody.create(body, JSON));
            if (sub.getSecretEncrypted() != null && !sub.getSecretEncrypted().isBlank()) {
                String secret = cryptoService.decryptPossiblyLegacy(sub.getSecretEncrypted());
                if (secret != null && !secret.isBlank()) {
                    builder.header("X-PM-Signature", "sha256=" + hmacSha256Hex(secret, ts + "." + body));
                }
            }
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                recordDelivery(subscriptionId, response.code(), response.isSuccessful());
            }
        } catch (Exception e) {
            log.warn("webhook delivery failed sub={} err={}", subscriptionId, e.getMessage());
            recordDelivery(subscriptionId, -1, false);
        }
    }

    // Runs on the dispatch thread; subscriptionRepository.save() opens its own tx.
    private void recordDelivery(Long subscriptionId, int status, boolean ok) {
        BotWebhookSubscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null) {
            return;
        }
        sub.setLastDeliveryStatus(status);
        sub.setLastDeliveryAt(LocalDateTime.now());
        if (ok) {
            sub.setConsecutiveFailures(0);
        } else {
            int failures = (sub.getConsecutiveFailures() == null ? 0 : sub.getConsecutiveFailures()) + 1;
            sub.setConsecutiveFailures(failures);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                sub.setIsActive(false);
                log.warn("webhook sub {} auto-disabled after {} consecutive failures", subscriptionId, failures);
            }
        }
        subscriptionRepository.save(sub);
    }

    private String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private void assertOwner(BotConfig bot, Long ownerId) {
        if (bot == null || bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(ownerId)) {
            throw new AccessDeniedException("只能管理自己创建的机器人的 webhook");
        }
    }

    private WebhookView toView(BotWebhookSubscription sub) {
        return new WebhookView(
                sub.getId(),
                sub.getBotConfig() != null ? sub.getBotConfig().getId() : null,
                sub.getCallbackUrl(),
                sub.getEventTypes(),
                sub.getChatRoomId(),
                sub.getIsActive(),
                sub.getSecretEncrypted() != null && !sub.getSecretEncrypted().isBlank(),
                sub.getLastDeliveryStatus(),
                sub.getConsecutiveFailures());
    }

    /** Safe view — never exposes the webhook secret. */
    public record WebhookView(
            Long id,
            Long botId,
            String callbackUrl,
            String eventTypes,
            Long chatRoomId,
            Boolean active,
            boolean hasSecret,
            Integer lastDeliveryStatus,
            Integer consecutiveFailures) {
    }
}
