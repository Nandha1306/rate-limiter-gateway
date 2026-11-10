package io.github.nandha1306.gateway.limiter.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.github.nandha1306.gateway.config.LimiterProperties;
import io.github.nandha1306.gateway.limiter.LimiterStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests the startup connection warm-up against a real Redis and an unreachable one.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisWarmupTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();

    @AfterAll
    static void disconnect() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
    }

    /**
     * Verifies that the warm-up succeeds against a running Redis.
     */
    @Test
    void warmsUpReachableRedis() {
        RedisWarmup warmup = new RedisWarmup(new LimiterProperties(LimiterStore.REDIS),
                client(REDIS.getHost(), REDIS.getMappedPort(6379)));

        assertThat(warmup.warmUp()).isTrue();
    }

    /**
     * Verifies that an unreachable Redis is reported as not ready without failing startup.
     */
    @Test
    void unreachableRedisDoesNotFailStartup() throws IOException {
        RedisWarmup warmup = new RedisWarmup(new LimiterProperties(LimiterStore.REDIS),
                client("127.0.0.1", closedPort()));

        assertThat(warmup.warmUp()).isFalse();
        assertThatNoException().isThrownBy(() -> warmup.run(new DefaultApplicationArguments()));
    }

    /**
     * Verifies that the memory store never contacts Redis at startup.
     */
    @Test
    void memoryStoreSkipsWarmup() throws IOException {
        StringRedisTemplate unreachable = client("127.0.0.1", closedPort());
        RedisWarmup warmup = new RedisWarmup(new LimiterProperties(LimiterStore.MEMORY), unreachable);

        long start = System.nanoTime();
        warmup.run(new DefaultApplicationArguments());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static StringRedisTemplate client(String host, int port) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100))
                .build();
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
        factory.afterPropertiesSet();
        factory.start();
        FACTORIES.add(factory);
        return new StringRedisTemplate(factory);
    }
}
