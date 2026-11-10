package io.github.nandha1306.gateway.proxy;

import io.github.nandha1306.gateway.config.RouteConfig;
import io.github.nandha1306.gateway.filter.ClientIdentity;
import io.github.nandha1306.gateway.filter.ErrorResponses;
import io.github.nandha1306.gateway.filter.GatewayAttributes;
import io.github.nandha1306.gateway.filter.GatewayHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Catch-all endpoint that proxies every request the filters let through to its route's backend.
 */
@Controller
public class ProxyController {
    static final String UNAVAILABLE_RETRY_AFTER_SECONDS = "5";

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    /**
     * Writes the backend's response for a routed and identified request, or 503 {@code no_healthy_backend} with
     * {@code Retry-After: 5} when the backend is unavailable.
     */
    @RequestMapping("/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RouteConfig route = (RouteConfig) request.getAttribute(GatewayAttributes.ROUTE);
        ClientIdentity client = (ClientIdentity) request.getAttribute(GatewayAttributes.CLIENT);
        if (route == null || client == null) {
            ErrorResponses.write(response, HttpStatus.NOT_FOUND, "no_route");
            return;
        }
        String correlationId = (String) request.getAttribute(GatewayAttributes.CORRELATION_ID);
        try {
            write(proxyService.forward(request, route, client, correlationId), response);
        } catch (UpstreamUnavailableException e) {
            response.setHeader(HttpHeaders.RETRY_AFTER, UNAVAILABLE_RETRY_AFTER_SECONDS);
            ErrorResponses.write(response, HttpStatus.SERVICE_UNAVAILABLE, "no_healthy_backend");
        }
    }

    private static void write(UpstreamResponse upstream, HttpServletResponse response) throws IOException {
        response.setStatus(upstream.status());
        ProxyHeaders.copyToClient(upstream.headers(), response);
        response.setHeader(GatewayHeaders.SERVED_BY, upstream.servedBy());
        response.setContentLength(upstream.body().length);
        response.getOutputStream().write(upstream.body());
    }
}
