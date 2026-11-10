package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Tests that the gateway refuses to start with invalid configuration and applies defaults to omitted values.
 */
class GatewayConfigCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConfigOnly.class)
            .withPropertyValues(
                    "gateway.upstreams.mock.urls[0]=http://localhost:8081",
                    "gateway.routes[0].id=orders",
                    "gateway.routes[0].path-prefix=/api/orders",
                    "gateway.routes[0].upstream=mock",
                    "gateway.routes[0].default-tier=free",
                    "gateway.routes[0].tiers.free.limit=60",
                    "gateway.routes[0].tiers.free.window-seconds=60");

    /**
     * Verifies that a minimal valid config starts, with algorithm, failure mode, burst and timeouts defaulted.
     */
    @Test
    void minimalConfigStartsWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GatewayProperties properties = context.getBean(GatewayProperties.class);
            RouteConfig orders = properties.routes().get(0);

            assertThat(orders.publicRoute()).isFalse();
            assertThat(orders.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
            assertThat(orders.failureMode()).isEqualTo(FailureMode.LOCAL_FALLBACK);
            assertThat(orders.limitFor("free").burst()).isZero();
            assertThat(orders.limitFor("free").capacity()).isEqualTo(60);
            assertThat(properties.timeouts().connect()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.timeouts().read()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    /**
     * Verifies that an invalid route stops startup with an error naming the route and the rule it broke.
     */
    @Test
    void invalidConfigFailsStartup() {
        runner.withPropertyValues("gateway.routes[0].path-prefix=api/orders", "gateway.routes[0].upstream=missing")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(InvalidGatewayConfigException.class)
                            .hasMessageContaining("routes[0] (orders): pathPrefix must start with '/'")
                            .hasMessageContaining("routes[0] (orders): upstream 'missing' is not defined");
                });
    }

    /**
     * Verifies that an out-of-range tier limit is rejected while binding, before validation runs.
     */
    @Test
    void invalidLimitFailsStartup() {
        runner.withPropertyValues("gateway.routes[0].tiers.free.limit=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("limit must be > 0");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    @Import(GatewayConfigCheck.class)
    static class ConfigOnly {
    }
}
