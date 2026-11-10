package io.github.nandha1306.gateway.limiter;

/**
 * What to rate limit for one request.
 *
 * @param routeId   route the request matched
 * @param tier      effective tier on that route
 * @param clientId  client the request comes from
 * @param algorithm algorithm configured for the route
 * @param spec      limit for that route and tier
 */
public record RateLimitRequest(String routeId, String tier, String clientId, Algorithm algorithm, LimitSpec spec) {
}
