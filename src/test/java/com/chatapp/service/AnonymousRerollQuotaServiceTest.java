package com.chatapp.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousRerollQuotaServiceTest {

    @Test
    void allowsOnlyThreeRerollsPerUserPerDay() {
        AnonymousRerollQuotaService service = serviceAt("2026-05-27T01:00:00Z");

        assertEquals(2, service.consume(7L).getRemaining());
        assertEquals(1, service.consume(7L).getRemaining());
        assertEquals(0, service.consume(7L).getRemaining());

        AnonymousRerollQuotaService.QuotaExceededException ex = assertThrows(
                AnonymousRerollQuotaService.QuotaExceededException.class,
                () -> service.consume(7L));
        assertEquals(0, ex.getSnapshot().getRemaining());
        assertTrue(ex.getSnapshot().getRetryAfterSeconds() > 0);
    }

    @Test
    void quotaKeyResetsAcrossUtc8DayBoundary() {
        MutableClock clock = new MutableClock("2026-05-27T15:55:00Z");
        AnonymousRerollQuotaService service = new AnonymousRerollQuotaService(null, clock);
        service.consume(9L);
        assertEquals(1, service.quota(9L).getUsed());

        clock.setInstant("2026-05-27T16:06:00Z");
        assertEquals(0, service.quota(9L).getUsed());
    }

    @Test
    void concurrentRequestsOnlyAllowThreeSuccesses() throws Exception {
        AnonymousRerollQuotaService service = serviceAt("2026-05-27T01:00:00Z");
        var executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Runnable> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            jobs.add(() -> {
                try {
                    start.await();
                    service.consume(11L);
                    success.incrementAndGet();
                } catch (AnonymousRerollQuotaService.QuotaExceededException ex) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        jobs.forEach(executor::submit);
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(3, success.get());
        assertEquals(2, rejected.get());
        assertEquals(3, service.quota(11L).getUsed());
    }

    private AnonymousRerollQuotaService serviceAt(String instant) {
        return new AnonymousRerollQuotaService(
                null,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(String instant) {
            this.instant = Instant.parse(instant);
        }

        void setInstant(String instant) {
            this.instant = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
