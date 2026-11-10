package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import io.github.nandha1306.gateway.limiter.LimitSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests longest-prefix route matching.
 */
class RouteMatcherTest {
    private final RouteMatcher matcher = new RouteMatcher(List.of(
            route("api", "/api"),
            route("orders", "/api/orders"),
            route("login", "/auth/login")));

    /**
     * Verifies that the most specific route wins when several prefixes match.
     */
    @Test
    void longestPrefixWins() {
        assertThat(idFor("/api/orders/1")).contains("orders");
        assertThat(idFor("/api/products")).contains("api");
    }

    /**
     * Verifies that a path equal to the prefix, or the prefix with a trailing slash, matches.
     */
    @Test
    void exactPrefixAndTrailingSlashMatch() {
        assertThat(idFor("/api/orders")).contains("orders");
        assertThat(idFor("/api/orders/")).contains("orders");
        assertThat(idFor("/auth/login")).contains("login");
    }

    /**
     * Verifies that a prefix only matches whole path segments, so /api/orders does not match /api/ordersXYZ.
     */
    @Test
    void prefixMatchesWholeSegmentsOnly() {
        RouteMatcher ordersOnly = new RouteMatcher(List.of(route("orders", "/api/orders")));

        assertThat(ordersOnly.match("/api/ordersXYZ")).isEmpty();
        assertThat(idFor("/api/ordersXYZ")).contains("api");
        assertThat(idFor("/auth/login-admin")).isEmpty();
    }

    /**
     * Verifies that unknown and missing paths match no route.
     */
    @Test
    void unknownPathMatchesNothing() {
        assertThat(idFor("/unknown")).isEmpty();
        assertThat(matcher.match(null)).isEmpty();
    }

    /**
     * Verifies that a root route catches every path that no more specific route matches.
     */
    @Test
    void rootPrefixIsCatchAll() {
        RouteMatcher withRoot = new RouteMatcher(List.of(route("root", "/"), route("orders", "/api/orders")));

        assertThat(withRoot.match("/anything/else").map(RouteConfig::id)).contains("root");
        assertThat(withRoot.match("/api/orders/9").map(RouteConfig::id)).contains("orders");
    }

    private Optional<String> idFor(String path) {
        return matcher.match(path).map(RouteConfig::id);
    }

    private static RouteConfig route(String id, String prefix) {
        return new RouteConfig(id, prefix, "mock", false, Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK,
                "free", Map.of("free", new LimitSpec(60, 60, 0)));
    }
}
