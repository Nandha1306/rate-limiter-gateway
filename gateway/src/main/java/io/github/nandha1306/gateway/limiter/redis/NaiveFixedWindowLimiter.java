package io.github.nandha1306.gateway.limiter.redis;

import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import io.github.nandha1306.gateway.limiter.RateLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed window counter in Redis that reads, checks and increments in separate calls, so concurrent gateways can all
 * pass the check before any of them increments. Exists only to demonstrate the race the Lua limiter avoids.
 */
@Component
public class NaiveFixedWindowLimiter implements RateLimiter {
    private static final long MAX_WINDOW_SECONDS = 2_592_000L;

    private final StringRedisTemplate redis;

    public NaiveFixedWindowLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Reads the window's count, denies if it has reached the limit, otherwise increments it and starts the window's
     * expiry on the first request.
     */
    @Override
    public RateLimitResult tryAcquire(String key, LimitSpec spec) {
        long windowSeconds = Math.min(spec.windowSeconds(), MAX_WINDOW_SECONDS);
        String current = redis.opsForValue().get(key);
        long count = current == null ? 0 : Long.parseLong(current);
        if (count >= spec.limit()) {
            Long ttlMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
            long retryAfterMs = ttlMs == null || ttlMs < 0 ? windowSeconds * 1000 : ttlMs;
            return new RateLimitResult(false, 0, Math.max(1, retryAfterMs));
        }
        Long updated = redis.opsForValue().increment(key);
        long newCount = updated == null ? count + 1 : updated;
        if (newCount == 1) {
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        }
        return new RateLimitResult(true, Math.max(0, spec.limit() - newCount), 0);
    }
}
