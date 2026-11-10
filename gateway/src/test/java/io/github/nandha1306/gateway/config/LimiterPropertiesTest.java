package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.LimiterStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests binding of the limiter store setting.
 */
class LimiterPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LimiterOnly.class);

    /**
     * Verifies that Redis is the default store when nothing is configured.
     */
    @Test
    void defaultsToRedis() {
        runner.run(context -> assertThat(context.getBean(LimiterProperties.class).store())
                .isEqualTo(LimiterStore.REDIS));
    }

    /**
     * Verifies that the store can be switched to memory.
     */
    @Test
    void canSwitchToMemory() {
        runner.withPropertyValues("gateway.limiter.store=MEMORY")
                .run(context -> assertThat(context.getBean(LimiterProperties.class).store())
                        .isEqualTo(LimiterStore.MEMORY));
    }

    /**
     * Verifies that an unknown store name stops startup instead of silently falling back.
     */
    @Test
    void unknownStoreFailsStartup() {
        runner.withPropertyValues("gateway.limiter.store=DISK")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LimiterProperties.class)
    static class LimiterOnly {
    }
}
