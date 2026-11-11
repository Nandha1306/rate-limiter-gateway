package io.github.nandha1306.gateway.filter;

/**
 * HTTP header names the gateway reads from clients and writes to clients and backends.
 */
public final class GatewayHeaders {
    public static final String API_KEY = "X-API-Key";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String CLIENT_ID = "X-Client-Id";
    public static final String SERVED_BY = "X-Served-By";
    public static final String GATEWAY_ID = "X-Gateway-Id";
    public static final String REAL_IP = "X-Real-IP";
    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    private GatewayHeaders() {
    }
}
