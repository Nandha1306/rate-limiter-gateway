package io.github.nandha1306.gateway.filter;

import io.github.nandha1306.gateway.config.RouteConfig;
import io.github.nandha1306.gateway.limiter.Algorithm;
import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitRequest;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import io.github.nandha1306.gateway.limiter.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the route's limit for the identified client and writes the rate limit response headers.
 */
@Component
@Order(FilterOrder.RATE_LIMIT)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService rateLimits;

    public RateLimitFilter(RateLimitService rateLimits) {
        this.rateLimits = rateLimits;
    }

    /**
     * Skips requests the auth filter did not identify, such as unrouted paths, actuator and rejected keys.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getAttribute(GatewayAttributes.CLIENT) == null;
    }

    /**
     * Lets an allowed request through with {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining}, or answers
     * 429 {@code rate_limited} with {@code Retry-After}; routes with algorithm NONE pass without headers.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteConfig route = (RouteConfig) request.getAttribute(GatewayAttributes.ROUTE);
        ClientIdentity client = (ClientIdentity) request.getAttribute(GatewayAttributes.CLIENT);
        if (route.algorithm() == Algorithm.NONE) {
            chain.doFilter(request, response);
            return;
        }

        LimitSpec spec = route.limitFor(client.tier());
        String tier = route.effectiveTier(client.tier());
        RateLimitResult result = rateLimits.check(
                new RateLimitRequest(route.id(), tier, client.clientId(), route.algorithm(), spec));

        response.setHeader(GatewayHeaders.RATE_LIMIT_LIMIT, String.valueOf(spec.capacity()));
        if (result.allowed()) {
            response.setHeader(GatewayHeaders.RATE_LIMIT_REMAINING, String.valueOf(result.remaining()));
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(GatewayHeaders.RATE_LIMIT_REMAINING, "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(result.retryAfterMs())));
        ErrorResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                Map.of("retryAfterMs", result.retryAfterMs()));
    }

    /**
     * Converts a retry delay to whole seconds for {@code Retry-After}, rounding up and never below 1.
     */
    static long retryAfterSeconds(long retryAfterMs) {
        long seconds = retryAfterMs / 1000 + (retryAfterMs % 1000 == 0 ? 0 : 1);
        return Math.max(1, seconds);
    }
}
