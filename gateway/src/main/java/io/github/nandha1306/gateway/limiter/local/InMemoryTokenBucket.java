package io.github.nandha1306.gateway.limiter.local;

import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import io.github.nandha1306.gateway.limiter.RateLimiter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe in-memory token bucket rate limiter that keeps one bucket per key.
 */
public class InMemoryTokenBucket implements RateLimiter {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    /**
     * Creates a limiter that reads time from the JVM's monotonic clock.
     */
    public InMemoryTokenBucket() {
        this(System::nanoTime);
    }

    /**
     * Creates a limiter that reads time from the given nanosecond clock.
     */
    InMemoryTokenBucket(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Refills the key's bucket for the elapsed time, then takes one token if available;
     * otherwise denies and returns how long until the next token is ready.
     */
    @Override
    public RateLimitResult tryAcquire(String key, LimitSpec spec) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(spec.capacity(), nanoTime.getAsLong()));
        synchronized (bucket) {
            long now = nanoTime.getAsLong();
            long elapsedNanos = now - bucket.lastRefillNanos;
            if (elapsedNanos > 0) {
                double elapsedSeconds = elapsedNanos / NANOS_PER_SECOND;
                double refill = elapsedSeconds * spec.refillPerSecond();
                bucket.tokens = Math.min(spec.capacity(), bucket.tokens + refill);
                bucket.lastRefillNanos = now;
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new RateLimitResult(true, (long) bucket.tokens, 0);
            }
            double missingTokens = 1.0 - bucket.tokens;
            double waitSeconds = missingTokens / spec.refillPerSecond();
            double waitMillis = waitSeconds * 1000.0;
            long retryAfterMs;
            if (!Double.isFinite(waitMillis) || waitMillis >= Long.MAX_VALUE) {
                retryAfterMs = Long.MAX_VALUE;
            } else {
                retryAfterMs = Math.max(1L, (long) Math.ceil(waitMillis));
            }
            return new RateLimitResult(false, 0, retryAfterMs);
        }
    }

    /**
     * Holds the token count and last refill time for one key.
     */
    private static class Bucket {
        private double tokens;
        private long lastRefillNanos;

        /**
         * Creates a full bucket with the given capacity, refilled as of {@code now}.
         */
        private Bucket(long capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }
    }
}
