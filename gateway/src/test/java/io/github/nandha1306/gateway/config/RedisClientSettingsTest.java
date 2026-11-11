package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.gateway.limiter.LimiterStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the Redis client settings and that the gateway stays healthy without Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedisClientSettingsTest {

    @Autowired
    private RedisProperties redis;

    @Autowired
    private LimiterProperties limiter;

    @Autowired
    private MockMvc mvc;

    /**
     * Verifies the 100 ms command timeout and 200 ms connect timeout, so a Redis outage fails fast instead of
     * hanging requests for Lettuce's 60 s default.
     */
    @Test
    void redisTimeoutsAreShort() {
        assertThat(redis.getTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(redis.getConnectTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(redis.getHost()).isEqualTo("localhost");
        assertThat(redis.getPort()).isEqualTo(6379);
    }

    /**
     * Verifies that tests run with the in-memory store, so they need no Redis.
     */
    @Test
    void testsUseMemoryStore() {
        assertThat(limiter.store()).isEqualTo(LimiterStore.MEMORY);
    }

    /**
     * Verifies that the gateway reports UP when no Redis is running, because Redis is not part of its health.
     */
    @Test
    void healthIsUpWithoutRedis() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
