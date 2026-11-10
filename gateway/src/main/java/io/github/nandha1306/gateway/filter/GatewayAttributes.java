package io.github.nandha1306.gateway.filter;

/**
 * Names of request attributes that filters use to pass results to later filters and the proxy.
 */
public final class GatewayAttributes {
    public static final String CORRELATION_ID = GatewayAttributes.class.getName() + ".correlationId";

    private GatewayAttributes() {
    }
}
