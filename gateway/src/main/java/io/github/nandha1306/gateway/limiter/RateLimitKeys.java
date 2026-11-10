package io.github.nandha1306.gateway.limiter;

/**
 * Builds rate limit keys in the format {@code rl:{routeId:tier:clientId}:algorithmCode}.
 */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /**
     * Returns the key for one client on one route and tier; the braces mark the Redis Cluster hash tag so all
     * of a client's keys for a route land on the same shard.
     */
    public static String build(String routeId, String tier, String clientId, String algorithmCode) {
        return "rl:{" + routeId + ":" + tier + ":" + clientId + "}:" + algorithmCode;
    }
}
