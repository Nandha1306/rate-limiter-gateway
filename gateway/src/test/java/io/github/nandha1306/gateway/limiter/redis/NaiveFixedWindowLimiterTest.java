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
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests the naive fixed window limiter against a real Redis. Concurrent tests assert only what is guaranteed and
 * measure the over-admission, because whether the race fires on a given run depends on thread timing.
 */
@Testcontainers(disabledWithoutDocker = true)
class NaiveFixedWindowLimiterTest {
    private static final Logger log = LoggerFactory.getLogger(NaiveFixedWindowLimiterTest.class);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    private static StringRedisTemplate redis;
    private static NaiveFixedWindowLimiter limiter;
    private static List<NaiveFixedWindowLimiter> threeGateways;

    @BeforeAll
    static void connect() {
        redis = newClient();
        limiter = new NaiveFixedWindowLimiter(redis);
        threeGateways = List.of(
                new NaiveFixedWindowLimiter(newClient()),
                new NaiveFixedWindowLimiter(newClient()),
                new NaiveFixedWindowLimiter(newClient()));
    }

    @AfterAll
    static void disconnect() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
    }

    /**
     * Verifies that, one request at a time, the window allows exactly the limit and then denies until it expires.
     */
    @Test
    void sequentialRequestsStopAtLimit() {
        String key = newKey();
        LimitSpec threePerHour = new LimitSpec(3, 3600, 0);

        assertThat(limiter.tryAcquire(key, threePerHour)).isEqualTo(new RateLimitResult(true, 2, 0));
        assertThat(limiter.tryAcquire(key, threePerHour)).isEqualTo(new RateLimitResult(true, 1, 0));
        assertThat(limiter.tryAcquire(key, threePerHour)).isEqualTo(new RateLimitResult(true, 0, 0));

        RateLimitResult denied = limiter.tryAcquire(key, threePerHour);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isBetween(3_590_000L, 3_600_000L);
    }

    /**
     * Verifies that the window is a plain string counter that expires after the window length.
     */
    @Test
    void storesCounterWithWindowExpiry() {
        String key = newKey();
        limiter.tryAcquire(key, new LimitSpec(10, 60, 0));

        assertThat(redis.type(key)).isEqualTo(DataType.STRING);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
    }

    /**
     * Verifies, with three Redis clients and 60 threads making 1020 attempts against a limit of 100, the guarantees
     * of the naive limiter: it never allows fewer than the limit, and the Redis counter equals the number allowed.
     * How far it goes over the limit is logged rather than asserted, since the race depends on timing.
     */
    @RepeatedTest(5)
    void concurrentRequestsNeverUnderAdmitAndOverAdmissionIsMeasured(RepetitionInfo repetition) throws Exception {
        String key = newKey();
        LimitSpec hundredPerHour = new LimitSpec(100, 3600, 0);
        int threadsPerClient = 20;
        int attemptsPerThread = 17;

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadsPerClient * threeGateways.size());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (NaiveFixedWindowLimiter gateway : threeGateways) {
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

        long counter = Long.parseLong(redis.opsForValue().get(key));
        log.info("Naive limiter run {}/{}: allowed {} of 1020 attempts with limit 100 (over by {})",
                repetition.getCurrentRepetition(), repetition.getTotalRepetitions(), allowed.get(), allowed.get() - 100);

        assertThat(allowed.get()).isGreaterThanOrEqualTo(100);
        assertThat(counter).isEqualTo(allowed.get());
    }

    private static String newKey() {
        return "rl:{test:free:" + UUID.randomUUID() + "}:nfw";
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
