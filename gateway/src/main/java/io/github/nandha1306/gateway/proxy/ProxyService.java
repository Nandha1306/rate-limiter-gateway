package io.github.nandha1306.gateway.proxy;

import io.github.nandha1306.gateway.config.GatewayProperties;
import io.github.nandha1306.gateway.config.RouteConfig;
import io.github.nandha1306.gateway.filter.ClientIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Forwards a routed request to its backend and returns the backend's response.
 */
@Service
public class ProxyService {
    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final RestClient restClient;
    private final GatewayProperties properties;

    public ProxyService(RestClient proxyRestClient, GatewayProperties properties) {
        this.restClient = proxyRestClient;
        this.properties = properties;
    }

    /**
     * Sends the request's method, path, query and body to the route's backend and returns its response, whatever
     * its status; throws {@link UpstreamUnavailableException} when the backend cannot be reached in time.
     */
    public UpstreamResponse forward(HttpServletRequest request, RouteConfig route, ClientIdentity client,
            String correlationId) throws IOException {
        URI backend = properties.upstreams().get(route.upstream()).urls().get(0);
        URI target = targetUri(backend, request.getRequestURI(), request.getQueryString());
        HttpHeaders headers = ProxyHeaders.forBackend(request, client.clientId(), correlationId);
        byte[] body = request.getInputStream().readAllBytes();

        RestClient.RequestBodySpec call = restClient.method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(outgoing -> outgoing.addAll(headers));
        if (body.length > 0) {
            call.body(body);
        }
        try {
            return call.exchange((outgoing, incoming) -> {
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.addAll(incoming.getHeaders());
                return new UpstreamResponse(incoming.getStatusCode().value(), responseHeaders,
                        incoming.getBody().readAllBytes(), backend.getAuthority());
            });
        } catch (ResourceAccessException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.warn("Upstream {} at {} unavailable for route {}: {} {}", route.upstream(), backend.getAuthority(),
                    route.id(), cause.getClass().getSimpleName(), cause.getMessage() == null ? "" : cause.getMessage());
            throw new UpstreamUnavailableException(route.upstream(), e);
        }
    }

    /**
     * Joins the backend base URL with the request's raw, still-encoded path and query string.
     */
    static URI targetUri(URI backend, String rawPath, String rawQuery) {
        String base = backend.toString().replaceAll("/+$", "");
        return URI.create(base + rawPath + (rawQuery == null ? "" : "?" + rawQuery));
    }
}
