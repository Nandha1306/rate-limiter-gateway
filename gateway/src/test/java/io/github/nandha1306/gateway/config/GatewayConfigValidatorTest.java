package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import io.github.nandha1306.gateway.limiter.LimitSpec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests each gateway configuration rule from plan section 8.
 */
class GatewayConfigValidatorTest {
    private static final Map<String, LimitSpec> TIERS = Map.of("free", new LimitSpec(60, 60, 10));
    private static final Map<String, UpstreamConfig> UPSTREAMS =
            Map.of("mock", new UpstreamConfig(List.of(URI.create("http://localhost:8081"))));
    private static final Map<String, ApiKeyConfig> API_KEYS =
            Map.of("demo-free-key-1", new ApiKeyConfig("user_1", "free"));
    private static final UpstreamTimeouts TIMEOUTS =
            new UpstreamTimeouts(Duration.ofMillis(500), Duration.ofSeconds(2));

    /**
     * Verifies that a complete, consistent configuration has no errors.
     */
    @Test
    void validConfigHasNoErrors() {
        assertThat(GatewayConfigValidator.validate(config(List.of(route("orders", "/api/orders"))))).isEmpty();
    }

    /**
     * Verifies that a configuration without routes is rejected.
     */
    @Test
    void atLeastOneRouteIsRequired() {
        assertThat(GatewayConfigValidator.validate(config(List.of())))
                .containsExactly("routes: at least one route is required");
    }

    /**
     * Verifies that a route's id, path prefix and upstream are required.
     */
    @Test
    void idPrefixAndUpstreamAreRequired() {
        RouteConfig route = new RouteConfig(" ", null, "", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "free", TIERS);

        assertThat(GatewayConfigValidator.validateRoute(route, UPSTREAMS.keySet()))
                .contains("id is required", "pathPrefix is required", "upstream is required");
    }

    /**
     * Verifies that a path prefix must start with a slash and must not end with one, except the root prefix.
     */
    @Test
    void pathPrefixMustBeWellFormed() {
        assertThat(errorsFor(route("orders", "api/orders"))).contains("pathPrefix must start with '/'");
        assertThat(errorsFor(route("orders", "/api/orders/"))).contains("pathPrefix must not end with '/'");
        assertThat(errorsFor(route("root", "/"))).isEmpty();
    }

    /**
     * Verifies that a route must point at an upstream that is defined.
     */
    @Test
    void upstreamMustExist() {
        RouteConfig route = new RouteConfig("orders", "/api/orders", "missing", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "free", TIERS);

        assertThat(errorsFor(route)).contains("upstream 'missing' is not defined");
    }

    /**
     * Verifies that a route needs at least one tier, and its default tier must be one of them.
     */
    @Test
    void tiersMustContainDefaultTier() {
        RouteConfig noTiers = new RouteConfig("orders", "/api/orders", "mock", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "free", Map.of());
        RouteConfig wrongDefault = new RouteConfig("orders", "/api/orders", "mock", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "gold", TIERS);

        assertThat(errorsFor(noTiers)).contains("at least one tier is required");
        assertThat(errorsFor(wrongDefault)).anySatisfy(error ->
                assertThat(error).startsWith("defaultTier 'gold' is not one of the tiers"));
    }

    /**
     * Verifies that a route's algorithm and failure mode are required.
     */
    @Test
    void algorithmAndFailureModeAreRequired() {
        RouteConfig route = new RouteConfig("orders", "/api/orders", "mock", false, null, null, "free", TIERS);

        assertThat(errorsFor(route)).contains("algorithm is required", "failureMode is required");
    }

    /**
     * Verifies that two routes may not share an id or a path prefix.
     */
    @Test
    void idsAndPrefixesMustBeUnique() {
        List<String> errors = GatewayConfigValidator.validate(config(List.of(
                route("orders", "/api/orders"),
                route("orders", "/api/orders"))));

        assertThat(errors).contains(
                "routes[1] (orders): duplicate id 'orders'",
                "routes[1] (orders): duplicate pathPrefix '/api/orders'");
    }

    /**
     * Verifies that every upstream needs at least one http or https URL with a host.
     */
    @Test
    void upstreamUrlsMustBeValid() {
        Map<String, UpstreamConfig> upstreams = Map.of(
                "mock", new UpstreamConfig(List.of(URI.create("http://localhost:8081"))),
                "empty", new UpstreamConfig(List.of()),
                "ftp", new UpstreamConfig(List.of(URI.create("ftp://files.example.com"))),
                "nohost", new UpstreamConfig(List.of(URI.create("http:///path"))));

        List<String> errors = GatewayConfigValidator.validate(
                new GatewayProperties(List.of(route("orders", "/api/orders")), API_KEYS, upstreams, TIMEOUTS));

        assertThat(errors).contains(
                "upstreams.empty: at least one url is required",
                "upstreams.ftp: url 'ftp://files.example.com' must use http or https",
                "upstreams.nohost: url 'http:///path' has no host");
    }

    /**
     * Verifies that every API key needs a client and a tier, and that errors never print the full key.
     */
    @Test
    void apiKeysNeedClientAndTierAndAreMasked() {
        Map<String, ApiKeyConfig> apiKeys = Map.of("secret-key-123", new ApiKeyConfig(" ", null));

        List<String> errors = GatewayConfigValidator.validate(
                new GatewayProperties(List.of(route("orders", "/api/orders")), apiKeys, UPSTREAMS, TIMEOUTS));

        assertThat(errors).contains(
                "apiKeys[secr****]: clientId is required",
                "apiKeys[secr****]: tier is required");
        assertThat(errors).noneSatisfy(error -> assertThat(error).contains("secret-key-123"));
    }

    /**
     * Verifies that backend call timeouts must be greater than zero.
     */
    @Test
    void timeoutsMustBePositive() {
        UpstreamTimeouts timeouts = new UpstreamTimeouts(Duration.ZERO, Duration.ofSeconds(-1));

        List<String> errors = GatewayConfigValidator.validate(
                new GatewayProperties(List.of(route("orders", "/api/orders")), API_KEYS, UPSTREAMS, timeouts));

        assertThat(errors).contains(
                "timeouts.connect must be greater than 0",
                "timeouts.read must be greater than 0");
    }

    /**
     * Verifies that every problem is reported at once, labelled with the route it belongs to.
     */
    @Test
    void reportsAllErrorsWithRouteLabels() {
        RouteConfig broken = new RouteConfig("orders", "api/orders", "missing", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "gold", TIERS);

        List<String> errors = GatewayConfigValidator.validate(config(List.of(broken)));

        assertThat(errors).hasSize(3).allSatisfy(error -> assertThat(error).startsWith("routes[0] (orders): "));
    }

    private static List<String> errorsFor(RouteConfig route) {
        return GatewayConfigValidator.validateRoute(route, UPSTREAMS.keySet());
    }

    private static GatewayProperties config(List<RouteConfig> routes) {
        return new GatewayProperties(routes, API_KEYS, UPSTREAMS, TIMEOUTS);
    }

    private static RouteConfig route(String id, String prefix) {
        return new RouteConfig(id, prefix, "mock", false,
                Algorithm.TOKEN_BUCKET, FailureMode.LOCAL_FALLBACK, "free", TIERS);
    }
}
