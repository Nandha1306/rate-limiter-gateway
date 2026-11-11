package io.github.nandha1306.gateway.limiter;

import io.github.nandha1306.gateway.config.LimiterProperties;
import io.github.nandha1306.gateway.limiter.local.InMemoryTokenBucket;
import io.github.nandha1306.gateway.limiter.redis.NaiveFixedWindowLimiter;
import io.github.nandha1306.gateway.limiter.redis.RedisTokenBucketLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Picks the limiter for a route's algorithm and the configured store, and checks one request against it.
 */
@Service
public class RateLimitService {
    static final String TOKEN_BUCKET_CODE = "tb";
    static final String NAIVE_FIXED_WINDOW_CODE = "nfw";

    private final LimiterStore store;
    private final RateLimiter memoryTokenBucket;
    private final RateLimiter redisTokenBucket;
    private final RateLimiter naiveFixedWindow;

    @Autowired
    public RateLimitService(LimiterProperties properties, RedisTokenBucketLimiter redisTokenBucket,
            NaiveFixedWindowLimiter naiveFixedWindow) {
        this(properties.store(), new InMemoryTokenBucket(), redisTokenBucket, naiveFixedWindow);
    }

    RateLimitService(LimiterStore store, RateLimiter memoryTokenBucket, RateLimiter redisTokenBucket,
            RateLimiter naiveFixedWindow) {
        this.store = store;
        this.memoryTokenBucket = memoryTokenBucket;
        this.redisTokenBucket = redisTokenBucket;
        this.naiveFixedWindow = naiveFixedWindow;
    }

    /**
     * Takes one permit for the request; {@code NONE} always allows, and the key's algorithm code is that of the
     * limiter that actually runs, so switching limiters never reuses a key holding another limiter's data.
     */
    public RateLimitResult check(RateLimitRequest request) {
        if (request.algorithm() == Algorithm.NONE) {
            return new RateLimitResult(true, request.spec().capacity(), 0);
        }
        SelectedLimiter selected = select(request.algorithm());
        String key = RateLimitKeys.build(request.routeId(), request.tier(), request.clientId(), selected.code());
        return selected.limiter().tryAcquire(key, request.spec());
    }

    /**
     * Returns the in-memory bucket for the memory store; for Redis, the naive limiter for the race demo and the Lua
     * token bucket for every other algorithm until the other Redis algorithms exist.
     */
    SelectedLimiter select(Algorithm algorithm) {
        if (store == LimiterStore.MEMORY) {
            return new SelectedLimiter(memoryTokenBucket, TOKEN_BUCKET_CODE);
        }
        if (algorithm == Algorithm.NAIVE_FIXED_WINDOW) {
            return new SelectedLimiter(naiveFixedWindow, NAIVE_FIXED_WINDOW_CODE);
        }
        return new SelectedLimiter(redisTokenBucket, TOKEN_BUCKET_CODE);
    }

    /**
     * A limiter together with the algorithm code used in its keys.
     */
    record SelectedLimiter(RateLimiter limiter, String code) {
    }
}
