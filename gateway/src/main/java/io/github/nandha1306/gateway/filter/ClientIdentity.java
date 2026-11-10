package io.github.nandha1306.gateway.filter;

/**
 * Who a request comes from, as decided by the auth filter.
 *
 * @param clientId client from the API key, or {@code ip:<address>} on public routes
 * @param tier     client's tier; the route may map an unlisted tier to its default tier
 */
public record ClientIdentity(String clientId, String tier) {

    /**
     * Returns the identity of an anonymous caller on a public route, keyed by IP address.
     */
    public static ClientIdentity forIp(String ip, String tier) {
        return new ClientIdentity("ip:" + ip, tier);
    }
}
