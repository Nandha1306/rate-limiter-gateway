package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tests that the gateway's application.yml binds to the routes, API keys, upstreams and timeouts from plan section 8.
 */
@SpringBootTest
class GatewayPropertiesBindingTest {

    @Autowired
    private GatewayProperties properties;

    /**
     * Verifies that the three starting routes bind in order with their paths, flags, algorithms and failure modes.
     */
    @Test
    void bindsStartingRoutes() {
        assertThat(properties.routes()).extracting(RouteConfig::id)
                .containsExactly("orders", "products", "login");

        RouteConfig orders = properties.routes().get(0);
        assertThat(orders.pathPrefix()).isEqualTo("/api/orders");
        assertThat(orders.upstream()).isEqualTo("mock");
        assertThat(orders.publicRoute()).isFalse();
        assertThat(orders.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
        assertThat(orders.failureMode()).isEqualTo(FailureMode.LOCAL_FALLBACK);

        RouteConfig products = properties.routes().get(1);
        assertThat(products.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_COUNTER);
        assertThat(products.failureMode()).isEqualTo(FailureMode.FAIL_OPEN);

        RouteConfig login = properties.routes().get(2);
        assertThat(login.publicRoute()).isTrue();
        assertThat(login.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_LOG);
        assertThat(login.defaultTier()).isEqualTo("anonymous");
    }

    /**
     * Verifies that tier limits bind correctly, including burst capacity and the derived refill rate.
     */
    @Test
    void bindsTierLimits() {
        RouteConfig orders = properties.routes().get(0);
        assertThat(orders.limitFor("free").capacity()).isEqualTo(10);
        assertThat(orders.limitFor("free").refillPerSecond()).isEqualTo(1.0);
        assertThat(orders.limitFor("premium").capacity()).isEqualTo(100);
        assertThat(orders.limitFor("premium").refillPerSecond()).isEqualTo(10.0);

        RouteConfig products = properties.routes().get(1);
        assertThat(products.limitFor("free").capacity()).isEqualTo(100);

        RouteConfig login = properties.routes().get(2);
        assertThat(login.limitFor("anonymous").limit()).isEqualTo(5);
        assertThat(login.limitFor("anonymous").windowSeconds()).isEqualTo(60);
    }

    /**
     * Verifies that the three demo API keys map to their clients and tiers.
     */
    @Test
    void bindsApiKeys() {
        assertThat(properties.apiKeys()).hasSize(3);
        assertThat(properties.apiKeys().get("demo-free-key-1")).isEqualTo(new ApiKeyConfig("user_1", "free"));
        assertThat(properties.apiKeys().get("demo-free-key-2")).isEqualTo(new ApiKeyConfig("user_2", "free"));
        assertThat(properties.apiKeys().get("demo-premium-key-3")).isEqualTo(new ApiKeyConfig("user_3", "premium"));
    }

    /**
     * Verifies that the mock upstream and the backend call timeouts bind.
     */
    @Test
    void bindsUpstreamAndTimeouts() {
        assertThat(properties.upstreams().get("mock").urls())
                .containsExactly(URI.create("http://localhost:8081"));
        assertThat(properties.timeouts().connect()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.timeouts().read()).isEqualTo(Duration.ofSeconds(2));
    }
}
