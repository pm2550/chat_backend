package com.chatapp.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitConfig {

    @Value("${rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${rate-limit.auth-requests-per-minute:10}")
    private int authRequestsPerMinute;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, LoginFailureCounter> loginFailures = new ConcurrentHashMap<>();

    public Bucket resolveBucket(String key) {
        return buckets.computeIfAbsent(key, k -> createBucket(requestsPerMinute));
    }

    public Bucket resolveAuthBucket(String key) {
        return buckets.computeIfAbsent("auth:" + key, k -> createBucket(authRequestsPerMinute));
    }

    public Bucket resolveSaltLookupBucket(String username) {
        return buckets.computeIfAbsent("salt:" + normalizeUsername(username),
                k -> Bucket.builder()
                        .addLimit(Bandwidth.classic(30, Refill.greedy(30, Duration.ofHours(1))))
                        .build());
    }

    public boolean isLoginLocked(String username) {
        LoginFailureCounter counter = loginFailures.get(normalizeUsername(username));
        return counter != null && !counter.isExpired() && counter.failures >= 50;
    }

    public void recordLoginFailure(String username) {
        loginFailures.compute(normalizeUsername(username), (key, existing) -> {
            LoginFailureCounter counter = existing == null || existing.isExpired()
                    ? new LoginFailureCounter()
                    : existing;
            counter.failures++;
            return counter;
        });
    }

    public void resetLoginFailures(String username) {
        loginFailures.remove(normalizeUsername(username));
    }

    private Bucket createBucket(int capacity) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class LoginFailureCounter {
        private int failures = 0;
        private final long windowStartMs = System.currentTimeMillis();

        private boolean isExpired() {
            return System.currentTimeMillis() - windowStartMs > Duration.ofHours(24).toMillis();
        }
    }
}
