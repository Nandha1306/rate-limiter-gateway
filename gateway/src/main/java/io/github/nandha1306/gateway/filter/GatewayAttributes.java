package io.github.nandha1306.gateway.filter;

/**
 * Names of request attributes that filters use to pass results to later filters and the proxy.
 */
public final class GatewayAttributes {
    public static final String CORRELATION_ID = GatewayAttributes.class.getName() + ".correlationId";
    public static final String ROUTE = GatewayAttributes.class.getName() + ".route";
    public static final String CLIENT = GatewayAttributes.class.getName() + ".client";

    private GatewayAttributes() {
    }
}
