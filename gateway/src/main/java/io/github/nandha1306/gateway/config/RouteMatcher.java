package io.github.nandha1306.gateway.config;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds the route for a request path: the route with the longest matching path prefix wins.
 */
@Component
public class RouteMatcher {
    private final List<RouteConfig> routesLongestPrefixFirst;

    @Autowired
    public RouteMatcher(GatewayProperties properties) {
        this(properties.routes());
    }

    RouteMatcher(List<RouteConfig> routes) {
        this.routesLongestPrefixFirst = routes.stream()
                .sorted(Comparator.comparingInt((RouteConfig route) -> route.pathPrefix().length()).reversed())
                .toList();
    }

    /**
     * Returns the route whose prefix matches the path, preferring the longest prefix; empty when none matches.
     */
    public Optional<RouteConfig> match(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (RouteConfig route : routesLongestPrefixFirst) {
            if (matches(route.pathPrefix(), path)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns true when the path is the prefix itself or lies below it, so {@code /api/orders} matches
     * {@code /api/orders/1} but not {@code /api/ordersXYZ}.
     */
    public static boolean matches(String prefix, String path) {
        if ("/".equals(prefix)) {
            return path.startsWith("/");
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
