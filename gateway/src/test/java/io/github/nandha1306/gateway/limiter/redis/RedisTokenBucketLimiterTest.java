package io.github.nandha1306.gateway.limiter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests the Lua token bucket against a real Redis. Skipped when Docker is not available; always runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketLimiterTest {
    private static final long MAX_RETRY_MS = 9_007_199_254_740_991L;
    private static final long MAX_TTL_MS = 2_592_000_000L;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    private static StringRedisTemplate redis;
    private static RedisTokenBucketLimiter limiter;
    private static List<RedisTokenBucketLimiter> threeGateways;

    @BeforeAll
    static void connect() {
        redis = newClient();
        limiter = new RedisTokenBucketLimiter(redis);
        threeGateways = List.of(
                new RedisTokenBucketLimiter(newClient()),
                new RedisTokenBucketLimiter(newClient()),
                new RedisTokenBucketLimiter(newClient()));
    }

    @AfterAll
    static void disconnect() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
    }

    /**
     * Verifies that a new bucket allows up to its capacity, then denies with retry-after for one token.
     */
    @Test
    void allowsUpToCapacityThenDenies() {
        String key = newKey();
        LimitSpec threePerHourBurst = new LimitSpec(1, 3600, 3);

        assertThat(limiter.tryAcquire(key, threePerHourBurst)).isEqualTo(new RateLimitResult(true, 2, 0));
        assertThat(limiter.tryAcquire(key, threePerHourBurst)).isEqualTo(new RateLimitResult(true, 1, 0));
        assertThat(limiter.tryAcquire(key, threePerHourBurst)).isEqualTo(new RateLimitResult(true, 0, 0));

        RateLimitResult denied = limiter.tryAcquire(key, threePerHourBurst);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMs()).isBetween(3_590_000L, 3_600_000L);
    }

    /**
     * Verifies that tokens refill as time passes on the Redis clock.
     */
    @Test
    void refillsOverTime() throws Exception {
        String key = newKey();
        LimitSpec tenPerSecondBurstOne = new LimitSpec(10, 1, 1);

        assertThat(limiter.tryAcquire(key, tenPerSecondBurstOne).allowed()).isTrue();
        RateLimitResult denied = limiter.tryAcquire(key, tenPerSecondBurstOne);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isBetween(1L, 100L);

        Thread.sleep(150);

        assertThat(limiter.tryAcquire(key, tenPerSecondBurstOne).allowed()).isTrue();
    }

    /**
     * Verifies that state is stored as a hash with an expiry of at most twice the full-refill time, so idle buckets
     * clean themselves up.
     */
    @Test
    void storesHashWithExpiry() {
        String key = newKey();
        limiter.tryAcquire(key, new LimitSpec(60, 60, 10));

        assertThat(redis.type(key)).isEqualTo(DataType.HASH);
        assertThat(redis.opsForHash().keys(key)).containsExactlyInAnyOrder("tokens", "ts");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 20_000L);
    }

    /**
     * Verifies that each key has its own bucket.
     */
    @Test
    void keysHaveSeparateBuckets() {
        String exhausted = newKey();
        LimitSpec onlyOne = new LimitSpec(1, 3600, 1);
        limiter.tryAcquire(exhausted, onlyOne);

        assertThat(limiter.tryAcquire(exhausted, onlyOne).allowed()).isFalse();
        assertThat(limiter.tryAcquire(newKey(), onlyOne).allowed()).isTrue();
    }

    /**
     * Verifies that a near-zero refill rate keeps retry-after and expiry within their caps instead of overflowing.
     */
    @Test
    void hugeWindowStaysWithinCaps() {
        String key = newKey();
        LimitSpec almostNoRefill = new LimitSpec(1, Long.MAX_VALUE, 2);

        limiter.tryAcquire(key, almostNoRefill);
        limiter.tryAcquire(key, almostNoRefill);
        RateLimitResult denied = limiter.tryAcquire(key, almostNoRefill);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isEqualTo(MAX_RETRY_MS);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, MAX_TTL_MS);
    }

    /**
     * Verifies that three separate Redis clients, standing in for three gateway instances, hammering one key from
     * 60 threads are allowed exactly the capacity of 100 in total. Refill is 1 token per hour, so the bound
     * burst + rate x elapsed stays exactly 100 for the length of the test.
     */
    @RepeatedTest(5)
    void threeClientsShareOneGlobalLimit() throws Exception {
        String key = newKey();
        LimitSpec hundredPerHour = new LimitSpec(1, 3600, 100);
        int threadsPerClient = 20;
        int attemptsPerThread = 17;

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadsPerClient * threeGateways.size());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (RedisTokenBucketLimiter gateway : threeGateways) {
                for (int t = 0; t < threadsPerClient; t++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        for (int i = 0; i < attemptsPerThread; i++) {
                            if (gateway.tryAcquire(key, hundredPerHour).allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(100);
    }

    private static String newKey() {
        return "rl:{test:free:" + UUID.randomUUID() + "}:tb";
    }

    private static StringRedisTemplate newClient() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        FACTORIES.add(factory);
        return new StringRedisTemplate(factory);
    }
}
