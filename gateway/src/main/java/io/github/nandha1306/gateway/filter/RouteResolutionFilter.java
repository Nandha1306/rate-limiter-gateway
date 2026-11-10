package io.github.nandha1306.gateway.filter;

import io.github.nandha1306.gateway.config.RouteConfig;
import io.github.nandha1306.gateway.config.RouteMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Finds the route for each request and stores it for the auth and rate limit filters and the proxy.
 */
@Component
@Order(FilterOrder.ROUTE_RESOLUTION)
public class RouteResolutionFilter extends OncePerRequestFilter {
    static final List<String> INTERNAL_PREFIXES = List.of("/actuator");

    private final RouteMatcher routeMatcher;

    public RouteResolutionFilter(RouteMatcher routeMatcher) {
        this.routeMatcher = routeMatcher;
    }

    /**
     * Skips the gateway's own endpoints, such as actuator, which are never routed to a backend.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = lookupPath(request);
        return INTERNAL_PREFIXES.stream().anyMatch(prefix -> RouteMatcher.matches(prefix, path));
    }

    /**
     * Stores the matching route as a request attribute, or answers 404 {@code no_route} when no route matches.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RouteConfig> route = routeMatcher.match(lookupPath(request));
        if (route.isEmpty()) {
            ErrorResponses.write(response, HttpStatus.NOT_FOUND, "no_route");
            return;
        }
        request.setAttribute(GatewayAttributes.ROUTE, route.get());
        chain.doFilter(request, response);
    }

    /**
     * Returns the decoded request path within the application, without the query string or path parameters.
     */
    static String lookupPath(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
