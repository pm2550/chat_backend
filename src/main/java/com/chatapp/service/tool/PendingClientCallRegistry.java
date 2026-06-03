package com.chatapp.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class PendingClientCallRegistry {
    private final Map<UUID, PendingClientCall> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingClientCallRegistry() {
        this(Clock.systemUTC());
    }

    PendingClientCallRegistry(Clock clock) {
        this.clock = clock;
    }

    public void register(UUID callId, Long userId, CompletableFuture<JsonNode> future, long timeoutMs) {
        pending.put(callId, new PendingClientCall(callId, userId, future, Instant.now(clock), timeoutMs));
    }

    public boolean complete(UUID callId, Long replyUserId, JsonNode result) {
        PendingClientCall call = pending.get(callId);
        if (call == null) {
            log.warn("Ignoring agent client tool result for unknown callId={}", callId);
            return false;
        }
        if (!call.userId().equals(replyUserId)) {
            log.warn("Dropping forged agent client tool result callId={} expectedUserId={} replyUserId={}",
                    callId, call.userId(), replyUserId);
            return false;
        }
        pending.remove(callId);
        call.future().complete(result);
        return true;
    }

    public boolean cancel(UUID callId) {
        PendingClientCall call = pending.remove(callId);
        if (call == null) {
            return false;
        }
        call.future().cancel(false);
        return true;
    }

    public int expireOld(Instant now) {
        int expired = 0;
        for (PendingClientCall call : pending.values()) {
            long ageMs = Duration.between(call.createdAt(), now).toMillis();
            if (ageMs > call.timeoutMs() + 5000) {
                if (pending.remove(call.callId(), call)) {
                    call.future().cancel(false);
                    expired++;
                    log.warn("Expired stale agent client tool call callId={} userId={} ageMs={}",
                            call.callId(), call.userId(), ageMs);
                }
            }
        }
        return expired;
    }

    @Scheduled(fixedRate = 5000)
    public void expireOldScheduled() {
        expireOld(Instant.now(clock));
    }

    public int pendingCount() {
        return pending.size();
    }

    public record PendingClientCall(
            UUID callId,
            Long userId,
            CompletableFuture<JsonNode> future,
            Instant createdAt,
            long timeoutMs) {
    }
}
