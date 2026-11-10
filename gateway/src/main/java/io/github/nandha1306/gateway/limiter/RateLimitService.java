package io.github.nandha1306.gateway.limiter;

import io.github.nandha1306.gateway.limiter.local.InMemoryTokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Picks the limiter for a route's algorithm and checks one request against it.
 */
@Service
public class RateLimitService {
    static final String TOKEN_BUCKET_CODE = "tb";

    private final RateLimiter tokenBucket;

    @Autowired
    public RateLimitService() {
        this(new InMemoryTokenBucket());
    }

    RateLimitService(RateLimiter tokenBucket) {
        this.tokenBucket = tokenBucket;
    }

    /**
     * Takes one permit for the request; {@code NONE} always allows, and every other algorithm is enforced
     * with the in-memory token bucket until the Redis algorithms exist.
     */
    public RateLimitResult check(RateLimitRequest request) {
        if (request.algorithm() == Algorithm.NONE) {
            return new RateLimitResult(true, request.spec().capacity(), 0);
        }
        String key = RateLimitKeys.build(request.routeId(), request.tier(), request.clientId(), TOKEN_BUCKET_CODE);
        return tokenBucket.tryAcquire(key, request.spec());
    }
}
