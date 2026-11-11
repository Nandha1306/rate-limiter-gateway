package io.github.nandha1306.gateway.limiter.redis;

import io.github.nandha1306.gateway.config.LimiterProperties;
import io.github.nandha1306.gateway.limiter.LimiterStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Opens the shared Redis connection at startup, so the first request does not pay the connection handshake within
 * the 100 ms command timeout.
 */
@Component
public class RedisWarmup implements ApplicationRunner {
    static final int ATTEMPTS = 10;
    static final Duration PAUSE_BETWEEN_ATTEMPTS = Duration.ofMillis(250);

    private static final Logger log = LoggerFactory.getLogger(RedisWarmup.class);

    private final LimiterProperties limiter;
    private final StringRedisTemplate redis;

    public RedisWarmup(LimiterProperties limiter, StringRedisTemplate redis) {
        this.limiter = limiter;
        this.redis = redis;
    }

    /**
     * Warms up the connection when limits are kept in Redis; never fails startup, because the gateway must start
     * even while Redis is down.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (limiter.store() == LimiterStore.REDIS) {
            warmUp();
        }
    }

    /**
     * Pings Redis up to {@value #ATTEMPTS} times, pausing between attempts, and returns whether it answered.
     */
    boolean warmUp() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                redis.execute((RedisCallback<String>) RedisConnection::ping);
                log.info("Redis connection ready (attempt {} of {})", attempt, ATTEMPTS);
                return true;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.info("Redis warm-up attempt {} of {} failed: {}", attempt, ATTEMPTS, e.getMessage());
                if (attempt < ATTEMPTS && !pause()) {
                    break;
                }
            }
        }
        log.warn("Redis not reachable at startup after {} attempts: {}", ATTEMPTS, lastFailure.getMessage());
        return false;
    }

    private static boolean pause() {
        try {
            Thread.sleep(PAUSE_BETWEEN_ATTEMPTS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
