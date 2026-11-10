package io.github.nandha1306.gateway.filter;

import org.springframework.core.Ordered;

/**
 * Fixed order of the gateway's request filters: correlation ID, route resolution, auth, rate limit.
 */
public final class FilterOrder {
    public static final int CORRELATION_ID = Ordered.HIGHEST_PRECEDENCE + 10;
    public static final int ROUTE_RESOLUTION = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final int AUTH = Ordered.HIGHEST_PRECEDENCE + 30;
    public static final int RATE_LIMIT = Ordered.HIGHEST_PRECEDENCE + 40;

    private FilterOrder() {
    }
}
