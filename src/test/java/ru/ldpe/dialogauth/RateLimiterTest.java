package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    final AtomicLong now = new AtomicLong();
    final RateLimiter limiter = new RateLimiter(now::get, 100);
    final UUID user = UUID.randomUUID();

    @Test void reconnectAndChangingIpDoNotResetUuid() {
        assertEquals(0, limiter.acquire(user, "one"));
        assertEquals(1000, limiter.acquire(user, "two"));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(0, limiter.acquire(user, "two"));
    }

    @Test void changingUuidDoesNotResetIp() {
        assertEquals(0, limiter.acquire(user, "one"));
        assertEquals(1000, limiter.acquire(UUID.randomUUID(), "one"));
    }

    @Test void failuresBackOffUpToSixtySeconds() {
        for (int i = 0; i < 10; i++) {
            assertEquals(0, limiter.acquire(user, "one"));
            limiter.failed(user, "one");
            long expected = Math.min(60, 1L << Math.min(i, 6)) * 1000;
            assertEquals(expected, limiter.acquire(user, "one"));
            now.addAndGet(Duration.ofMillis(expected).toNanos());
        }
    }

    @Test void idleEntriesExpire() {
        assertEquals(0, limiter.acquire(user, "one"));
        limiter.failed(user, "one");
        now.addAndGet(Duration.ofMinutes(16).toNanos());
        assertEquals(0, limiter.acquire(user, "one"));
        limiter.failed(user, "one");
        assertEquals(1000, limiter.acquire(user, "one"));
    }

    @Test void fullCacheFailsClosedWithoutEvictingLiveBan() {
        RateLimiter tiny = new RateLimiter(now::get, 1);
        assertEquals(0, tiny.acquire(user, "one"));
        assertEquals(60000, tiny.acquire(UUID.randomUUID(), "two"));
        assertEquals(1000, tiny.acquire(user, "one"));
        now.addAndGet(Duration.ofMinutes(16).toNanos());
        assertEquals(0, tiny.acquire(UUID.randomUUID(), "two"));
    }

    @Test void worksWithNegativeMonotonicClock() {
        now.set(-10000000000L);
        assertEquals(0, limiter.acquire(user, "one"));
        assertEquals(1000, limiter.acquire(user, "one"));
    }
}
