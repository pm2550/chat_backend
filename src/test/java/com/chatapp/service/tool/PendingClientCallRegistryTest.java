package com.chatapp.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingClientCallRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completeWithMatchingUserResolvesFuture() throws Exception {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        UUID callId = UUID.randomUUID();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        registry.register(callId, 7L, future, 1000);

        boolean accepted = registry.complete(callId, 7L, objectMapper.createObjectNode().put("ok", true));

        assertTrue(accepted);
        assertTrue(future.get(1, TimeUnit.SECONDS).path("ok").asBoolean());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void completeWithMismatchedUserIsIgnored() {
        PendingClientCallRegistry registry = new PendingClientCallRegistry();
        UUID callId = UUID.randomUUID();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        registry.register(callId, 7L, future, 1000);

        boolean accepted = registry.complete(callId, 8L, objectMapper.createObjectNode().put("forged", true));

        assertFalse(accepted);
        assertFalse(future.isDone());
        assertEquals(1, registry.pendingCount());
    }

    @Test
    void expireOldRemovesStaleCalls() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        PendingClientCallRegistry registry = new PendingClientCallRegistry(clock);
        UUID callId = UUID.randomUUID();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        registry.register(callId, 7L, future, 1000);

        int expired = registry.expireOld(Instant.parse("2026-06-01T00:00:07Z"));

        assertEquals(1, expired);
        assertEquals(0, registry.pendingCount());
        assertTrue(future.isCancelled());
        assertThrows(Exception.class, () -> future.get(1, TimeUnit.MILLISECONDS));
    }
}
