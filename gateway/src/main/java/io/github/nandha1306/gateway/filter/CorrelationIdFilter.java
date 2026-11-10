package io.github.nandha1306.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID, reusing a safe incoming one, and exposes it to logs, later
 * filters, the backend call and the response.
 */
@Component
@Order(FilterOrder.CORRELATION_ID)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Resolves the correlation ID, puts it in the logging context, request attributes and response header,
     * runs the rest of the chain, and always clears the logging context afterwards.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(GatewayHeaders.CORRELATION_ID));
        request.setAttribute(GatewayAttributes.CORRELATION_ID, correlationId);
        response.setHeader(GatewayHeaders.CORRELATION_ID, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Returns the incoming ID if it is 1-64 letters, digits, '-' or '_'; otherwise a new random UUID.
     */
    static String resolve(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
