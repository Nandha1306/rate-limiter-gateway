package io.github.nandha1306.gateway.filter;

import io.github.nandha1306.gateway.config.InstanceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every response with the ID of the gateway instance that handled it, so a client behind the load balancer can
 * see which instance answered.
 */
@Component
@Order(FilterOrder.GATEWAY_ID)
public class GatewayIdFilter extends OncePerRequestFilter {
    private final String gatewayId;

    public GatewayIdFilter(InstanceProperties instance) {
        this.gatewayId = instance.id();
    }

    /**
     * Sets {@code X-Gateway-Id} before the rest of the chain runs, so even error responses carry it.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(GatewayHeaders.GATEWAY_ID, gatewayId);
        chain.doFilter(request, response);
    }
}
