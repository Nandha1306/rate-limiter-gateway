package io.github.nandha1306.gateway.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Static gateway configuration bound from the {@code gateway.*} properties.
 *
 * @param routes    routes, matched by longest path prefix
 * @param apiKeys   API key to client mapping
 * @param upstreams backend pools by name
 * @param timeouts  time limits for backend calls
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        List<RouteConfig> routes,
        Map<String, ApiKeyConfig> apiKeys,
        Map<String, UpstreamConfig> upstreams,
        @DefaultValue UpstreamTimeouts timeouts) {

    public GatewayProperties {
        routes = routes == null ? List.of() : List.copyOf(routes);
        apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
        upstreams = upstreams == null ? Map.of() : Map.copyOf(upstreams);
    }
}
