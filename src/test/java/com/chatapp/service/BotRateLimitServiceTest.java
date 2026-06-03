package com.chatapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRateLimitServiceTest {

    @Test
    void agentRunIsLimitedPerRoomAndBot() {
        BotRateLimitService service = new BotRateLimitService();
        ReflectionTestUtils.setField(service, "agentRunsPerMinute", 2);

        assertTrue(service.tryAcquireAgentRun(1L, 10L));
        assertTrue(service.tryAcquireAgentRun(1L, 10L));
        assertFalse(service.tryAcquireAgentRun(1L, 10L), "third run in window should be denied");
    }

    @Test
    void differentRoomBotPairsHaveIndependentBuckets() {
        BotRateLimitService service = new BotRateLimitService();
        ReflectionTestUtils.setField(service, "agentRunsPerMinute", 1);

        assertTrue(service.tryAcquireAgentRun(1L, 10L));
        assertFalse(service.tryAcquireAgentRun(1L, 10L));
        // Different bot in same room: separate bucket.
        assertTrue(service.tryAcquireAgentRun(1L, 11L));
        // Different room, same bot: separate bucket.
        assertTrue(service.tryAcquireAgentRun(2L, 10L));
    }

    @Test
    void nonPositiveCapacityDisablesLimit() {
        BotRateLimitService service = new BotRateLimitService();
        ReflectionTestUtils.setField(service, "agentRunsPerMinute", 0);

        for (int i = 0; i < 100; i++) {
            assertTrue(service.tryAcquireAgentRun(1L, 10L), "0 capacity means unlimited");
        }
    }

    @Test
    void webSearchIsLimitedPerRoom() {
        BotRateLimitService service = new BotRateLimitService();
        ReflectionTestUtils.setField(service, "webSearchPerMinute", 1);

        assertTrue(service.tryAcquireWebSearch(5L));
        assertFalse(service.tryAcquireWebSearch(5L));
        assertTrue(service.tryAcquireWebSearch(6L));
    }

    @Test
    void genericAcquireRespectsCapacity() {
        BotRateLimitService service = new BotRateLimitService();
        assertTrue(service.tryAcquire("k", 1, Duration.ofMinutes(1)));
        assertFalse(service.tryAcquire("k", 1, Duration.ofMinutes(1)));
        // unlimited when capacity <= 0
        assertTrue(service.tryAcquire("z", 0, Duration.ofMinutes(1)));
        assertTrue(service.tryAcquire("z", 0, Duration.ofMinutes(1)));
    }
}
