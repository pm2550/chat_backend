package com.chatapp.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-principal (per-room / per-bot) rate limiter for bot-driven work.
 *
 * <p>{@link RateLimitConfig} (the HTTP filter) only buckets per client IP; agent
 * runs and bot tool calls are not HTTP-shaped, so they need their own per-principal
 * buckets. This is the single shared limiter that Phase-0 unification (agent runs)
 * and later features (web search caps, external bot tokens, moderation actions)
 * consume, instead of each inventing its own counter.
 *
 * <p>A configured limit of {@code <= 0} disables that limit (always allows), which
 * keeps unit tests that construct this service directly from being throttled.
 */
@Service
public class BotRateLimitService {

    @Value("${bot.rate-limit.agent-runs-per-minute:30}")
    private int agentRunsPerMinute;

    @Value("${bot.rate-limit.web-search-per-minute:60}")
    private int webSearchPerMinute;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** One agent-loop run, scoped per (room, bot). */
    public boolean tryAcquireAgentRun(Long roomId, Long botConfigId) {
        return tryAcquire("agent:" + key(roomId) + ":" + key(botConfigId),
                agentRunsPerMinute, Duration.ofMinutes(1));
    }

    /** One web search, scoped per room. */
    public boolean tryAcquireWebSearch(Long roomId) {
        return tryAcquire("search:" + key(roomId), webSearchPerMinute, Duration.ofMinutes(1));
    }

    /**
     * Generic per-key token-bucket acquire. {@code capacity <= 0} means "no limit".
     * The bucket identity includes the capacity+window so reconfiguration cannot
     * silently reuse a bucket built for a different limit.
     */
    public boolean tryAcquire(String scopedKey, long capacity, Duration window) {
        if (capacity <= 0) {
            return true;
        }
        String bucketKey = scopedKey + "|" + capacity + "|" + window.toMillis();
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> {
            Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, window));
            return Bucket.builder().addLimit(limit).build();
        });
        return bucket.tryConsume(1);
    }

    private static String key(Long value) {
        return value == null ? "global" : value.toString();
    }
}
