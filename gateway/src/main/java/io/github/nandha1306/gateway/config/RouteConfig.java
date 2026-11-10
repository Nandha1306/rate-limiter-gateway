package io.github.nandha1306.gateway.config;

import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.FailureMode;
import io.github.nandha1306.gateway.limiter.LimitSpec;
import java.util.Map;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One gateway route: which requests it matches, where they go, and how they are rate limited.
 *
 * @param id          unique route name
 * @param pathPrefix  matches this exact path or any path below it
 * @param upstream    name of the backend pool requests are proxied to
 * @param publicRoute if true, no API key is needed and clients are limited per IP
 * @param algorithm   rate limiting algorithm
 * @param failureMode behavior when the shared rate limit store is unavailable
 * @param defaultTier tier used when the client's tier is not listed, and always on public routes
 * @param tiers       limit for each tier name
 */
public record RouteConfig(
        String id,
        String pathPrefix,
        String upstream,
        boolean publicRoute,
        @DefaultValue("TOKEN_BUCKET") Algorithm algorithm,
        @DefaultValue("LOCAL_FALLBACK") FailureMode failureMode,
        String defaultTier,
        Map<String, LimitSpec> tiers) {

    public RouteConfig {
        tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
    }

    /**
     * Returns the tier that applies to a client: its own tier if this route lists it, otherwise the default tier.
     */
    public String effectiveTier(String clientTier) {
        return clientTier != null && tiers.containsKey(clientTier) ? clientTier : defaultTier;
    }

    /**
     * Returns the limit that applies to a client of the given tier.
     */
    public LimitSpec limitFor(String clientTier) {
        return tiers.get(effectiveTier(clientTier));
    }
}
