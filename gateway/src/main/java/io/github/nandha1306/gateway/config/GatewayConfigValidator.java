package io.github.nandha1306.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks gateway configuration against the rules in plan section 8 and reports every problem found.
 */
public final class GatewayConfigValidator {

    private GatewayConfigValidator() {
    }

    /**
     * Returns every problem in the whole gateway configuration; empty when it is valid.
     */
    public static List<String> validate(GatewayProperties properties) {
        List<String> errors = new ArrayList<>();
        if (properties.routes().isEmpty()) {
            errors.add("routes: at least one route is required");
        }

        Set<String> upstreamNames = properties.upstreams().keySet();
        Set<String> ids = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        List<RouteConfig> routes = properties.routes();
        for (int i = 0; i < routes.size(); i++) {
            RouteConfig route = routes.get(i);
            String label = routeLabel(i, route);
            for (String error : validateRoute(route, upstreamNames)) {
                errors.add(label + ": " + error);
            }
            if (!isBlank(route.id()) && !ids.add(route.id())) {
                errors.add(label + ": duplicate id '" + route.id() + "'");
            }
            if (!isBlank(route.pathPrefix()) && !prefixes.add(route.pathPrefix())) {
                errors.add(label + ": duplicate pathPrefix '" + route.pathPrefix() + "'");
            }
        }

        for (Map.Entry<String, UpstreamConfig> upstream : properties.upstreams().entrySet()) {
            errors.addAll(validateUpstream(upstream.getKey(), upstream.getValue()));
        }
        for (Map.Entry<String, ApiKeyConfig> apiKey : properties.apiKeys().entrySet()) {
            errors.addAll(validateApiKey(apiKey.getKey(), apiKey.getValue()));
        }
        errors.addAll(validateTimeouts(properties.timeouts()));
        return errors;
    }

    /**
     * Returns every problem in one route, checked against the known upstream names; empty when it is valid.
     */
    public static List<String> validateRoute(RouteConfig route, Set<String> upstreamNames) {
        List<String> errors = new ArrayList<>();
        if (isBlank(route.id())) {
            errors.add("id is required");
        }

        String prefix = route.pathPrefix();
        if (isBlank(prefix)) {
            errors.add("pathPrefix is required");
        } else if (!prefix.startsWith("/")) {
            errors.add("pathPrefix must start with '/'");
        } else if (prefix.length() > 1 && prefix.endsWith("/")) {
            errors.add("pathPrefix must not end with '/'");
        }

        if (isBlank(route.upstream())) {
            errors.add("upstream is required");
        } else if (!upstreamNames.contains(route.upstream())) {
            errors.add("upstream '" + route.upstream() + "' is not defined");
        }

        if (route.algorithm() == null) {
            errors.add("algorithm is required");
        }
        if (route.failureMode() == null) {
            errors.add("failureMode is required");
        }

        if (route.tiers().isEmpty()) {
            errors.add("at least one tier is required");
        }
        if (isBlank(route.defaultTier())) {
            errors.add("defaultTier is required");
        } else if (!route.tiers().isEmpty() && !route.tiers().containsKey(route.defaultTier())) {
            errors.add("defaultTier '" + route.defaultTier() + "' is not one of the tiers " + route.tiers().keySet());
        }
        return errors;
    }

    private static List<String> validateUpstream(String name, UpstreamConfig upstream) {
        List<String> errors = new ArrayList<>();
        String label = "upstreams." + name;
        if (upstream == null || upstream.urls().isEmpty()) {
            errors.add(label + ": at least one url is required");
            return errors;
        }
        for (URI url : upstream.urls()) {
            String scheme = url.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                errors.add(label + ": url '" + url + "' must use http or https");
            } else if (url.getHost() == null) {
                errors.add(label + ": url '" + url + "' has no host");
            }
        }
        return errors;
    }

    private static List<String> validateApiKey(String key, ApiKeyConfig apiKey) {
        List<String> errors = new ArrayList<>();
        String label = "apiKeys[" + mask(key) + "]";
        if (isBlank(key)) {
            errors.add("apiKeys: a key must not be blank");
        }
        if (apiKey == null || isBlank(apiKey.clientId())) {
            errors.add(label + ": clientId is required");
        }
        if (apiKey == null || isBlank(apiKey.tier())) {
            errors.add(label + ": tier is required");
        }
        return errors;
    }

    private static List<String> validateTimeouts(UpstreamTimeouts timeouts) {
        List<String> errors = new ArrayList<>();
        if (timeouts == null) {
            errors.add("timeouts: connect and read are required");
            return errors;
        }
        if (!isPositive(timeouts.connect())) {
            errors.add("timeouts.connect must be greater than 0");
        }
        if (!isPositive(timeouts.read())) {
            errors.add("timeouts.read must be greater than 0");
        }
        return errors;
    }

    private static String routeLabel(int index, RouteConfig route) {
        return isBlank(route.id()) ? "routes[" + index + "]" : "routes[" + index + "] (" + route.id() + ")";
    }

    private static String mask(String key) {
        if (key == null || key.length() <= 4) {
            return "****";
        }
        return key.substring(0, 4) + "****";
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
