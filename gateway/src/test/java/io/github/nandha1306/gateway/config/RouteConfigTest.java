package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import io.github.nandha1306.gateway.limiter.LimitSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests how a route picks the tier and limit that apply to a client.
 */
class RouteConfigTest {
    private static final LimitSpec FREE = new LimitSpec(60, 60, 10);
    private static final LimitSpec PREMIUM = new LimitSpec(600, 60, 100);

    private final RouteConfig route = new RouteConfig("orders", "/api/orders", "mock", false,
            Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "free", Map.of("free", FREE, "premium", PREMIUM));

    /**
     * Verifies that a client whose tier the route lists gets that tier and its limit.
     */
    @Test
    void listedTierIsUsed() {
        assertThat(route.effectiveTier("premium")).isEqualTo("premium");
        assertThat(route.limitFor("premium")).isEqualTo(PREMIUM);
    }

    /**
     * Verifies that a client whose tier the route does not list falls back to the default tier.
     */
    @Test
    void unlistedTierFallsBackToDefault() {
        assertThat(route.effectiveTier("gold")).isEqualTo("free");
        assertThat(route.limitFor("gold")).isEqualTo(FREE);
    }

    /**
     * Verifies that a client with no tier falls back to the default tier.
     */
    @Test
    void missingTierFallsBackToDefault() {
        assertThat(route.effectiveTier(null)).isEqualTo("free");
        assertThat(route.limitFor(null)).isEqualTo(FREE);
    }
}
