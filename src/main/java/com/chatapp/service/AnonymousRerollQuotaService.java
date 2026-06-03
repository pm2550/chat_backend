package com.chatapp.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class AnonymousRerollQuotaService {

    public static final int DAILY_LIMIT = 3;
    private static final ZoneId RESET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KEY_PREFIX = "anon:reroll:";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Map<String, AtomicInteger> memoryCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    @Autowired
    public AnonymousRerollQuotaService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider.getIfAvailable(), Clock.systemUTC());
    }

    AnonymousRerollQuotaService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public QuotaSnapshot consume(Long userId) {
        try {
            return consumeRedis(userId);
        } catch (RuntimeException ex) {
            warnFallback(ex);
            return consumeMemory(userId);
        }
    }

    public void release(Long userId) {
        try {
            releaseRedis(userId);
            return;
        } catch (RuntimeException ex) {
            warnFallback(ex);
        }
        memoryCounts.computeIfAbsent(key(userId), ignored -> new AtomicInteger())
                .updateAndGet(value -> Math.max(0, value - 1));
    }

    public QuotaSnapshot quota(Long userId) {
        try {
            return quotaRedis(userId);
        } catch (RuntimeException ex) {
            warnFallback(ex);
            int used = memoryCounts.getOrDefault(key(userId), new AtomicInteger()).get();
            return snapshot(Math.min(used, DAILY_LIMIT));
        }
    }

    private QuotaSnapshot consumeRedis(Long userId) {
        if (redisTemplate == null) {
            throw new IllegalStateException("Redis template is not available");
        }
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        if (ops == null) {
            throw new IllegalStateException("Redis value operations are not available");
        }
        String key = key(userId);
        Long count = ops.increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis INCR returned null");
        }
        redisTemplate.expire(key, Duration.between(Instant.now(clock), resetInstant()));
        if (count > DAILY_LIMIT) {
            ops.increment(key, -1L);
            throw new QuotaExceededException(snapshot(DAILY_LIMIT));
        }
        return snapshot(count.intValue());
    }

    private void releaseRedis(Long userId) {
        if (redisTemplate == null) {
            throw new IllegalStateException("Redis template is not available");
        }
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        if (ops == null) {
            throw new IllegalStateException("Redis value operations are not available");
        }
        ops.increment(key(userId), -1L);
    }

    private QuotaSnapshot quotaRedis(Long userId) {
        if (redisTemplate == null) {
            throw new IllegalStateException("Redis template is not available");
        }
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        if (ops == null) {
            throw new IllegalStateException("Redis value operations are not available");
        }
        String value = ops.get(key(userId));
        int used = value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        return snapshot(Math.min(Math.max(used, 0), DAILY_LIMIT));
    }

    private QuotaSnapshot consumeMemory(Long userId) {
        AtomicInteger counter = memoryCounts.computeIfAbsent(key(userId), ignored -> new AtomicInteger());
        int count = counter.incrementAndGet();
        if (count > DAILY_LIMIT) {
            counter.decrementAndGet();
            throw new QuotaExceededException(snapshot(DAILY_LIMIT));
        }
        return snapshot(count);
    }

    private void warnFallback(RuntimeException ex) {
        if (fallbackWarned.compareAndSet(false, true)) {
            log.warn("Redis unavailable for anonymous reroll quota; falling back to in-memory counters: {}",
                    ex.getMessage());
        }
    }

    private String key(Long userId) {
        String day = ZonedDateTime.now(clock).withZoneSameInstant(RESET_ZONE)
                .toLocalDate()
                .toString()
                .replace("-", "");
        return KEY_PREFIX + userId + ":" + day;
    }

    private QuotaSnapshot snapshot(int used) {
        int normalizedUsed = Math.min(Math.max(used, 0), DAILY_LIMIT);
        Instant reset = resetInstant();
        long retryAfterSeconds = Math.max(0, Duration.between(Instant.now(clock), reset).getSeconds());
        return new QuotaSnapshot(
                normalizedUsed,
                Math.max(0, DAILY_LIMIT - normalizedUsed),
                reset.atZone(RESET_ZONE).toOffsetDateTime().toString(),
                retryAfterSeconds);
    }

    private Instant resetInstant() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(RESET_ZONE);
        return now.toLocalDate()
                .plusDays(1)
                .atTime(0, 5)
                .atZone(RESET_ZONE)
                .toInstant();
    }

    @Getter
    public static class QuotaSnapshot {
        private final int used;
        private final int remaining;
        private final String resetsAt;
        private final long retryAfterSeconds;

        QuotaSnapshot(int used, int remaining, String resetsAt, long retryAfterSeconds) {
            this.used = used;
            this.remaining = remaining;
            this.resetsAt = resetsAt;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        @Getter
        private final QuotaSnapshot snapshot;

        QuotaExceededException(QuotaSnapshot snapshot) {
            super("今日匿名身份切换次数已用完，请明天再试");
            this.snapshot = snapshot;
        }
    }
}
