package io.github.nandha1306.gateway.limiter.redis;

import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import io.github.nandha1306.gateway.limiter.RateLimiter;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket rate limiter whose state lives in Redis and is updated atomically by a Lua script, so every gateway
 * instance shares the same buckets.
 */
@Component
public class RedisTokenBucketLimiter implements RateLimiter {
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("lua/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucketLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Runs the token bucket script for the key in one atomic Redis call and converts its reply to a result.
     */
    @Override
    public RateLimitResult tryAcquire(String key, LimitSpec spec) {
        List<?> reply = redis.execute(SCRIPT, List.of(key),
                String.valueOf(spec.capacity()), String.valueOf(spec.refillPerSecond()));
        if (reply == null || reply.size() != 3) {
            throw new IllegalStateException("Unexpected token bucket script reply: " + reply);
        }
        return new RateLimitResult(toLong(reply.get(0)) == 1, toLong(reply.get(1)), toLong(reply.get(2)));
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
