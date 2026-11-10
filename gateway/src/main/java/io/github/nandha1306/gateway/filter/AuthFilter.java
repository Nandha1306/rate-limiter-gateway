package io.github.nandha1306.gateway.filter;

import io.github.nandha1306.gateway.config.RouteConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Identifies the client of each routed request: by API key on private routes, by IP address on public routes.
 */
@Component
@Order(FilterOrder.AUTH)
public class AuthFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "clientId";

    private final ApiKeyRegistry apiKeys;

    public AuthFilter(ApiKeyRegistry apiKeys) {
        this.apiKeys = apiKeys;
    }

    /**
     * Skips requests that route resolution did not assign to a route, such as actuator calls.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getAttribute(GatewayAttributes.ROUTE) == null;
    }

    /**
     * Stores the client's identity for the rate limit filter and the proxy, or answers 401
     * {@code invalid_api_key} when a private route gets a missing or unknown key.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteConfig route = (RouteConfig) request.getAttribute(GatewayAttributes.ROUTE);
        Optional<ClientIdentity> client = identify(route, request);
        if (client.isEmpty()) {
            ErrorResponses.write(response, HttpStatus.UNAUTHORIZED, "invalid_api_key");
            return;
        }
        request.setAttribute(GatewayAttributes.CLIENT, client.get());
        MDC.put(MDC_KEY, client.get().clientId());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private Optional<ClientIdentity> identify(RouteConfig route, HttpServletRequest request) {
        if (route.publicRoute()) {
            return Optional.of(ClientIdentity.forIp(ClientIpResolver.resolve(request), route.defaultTier()));
        }
        return apiKeys.lookup(request.getHeader(GatewayHeaders.API_KEY));
    }
}
