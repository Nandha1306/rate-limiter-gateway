package io.github.nandha1306.gateway.proxy;

import io.github.nandha1306.gateway.filter.GatewayHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;

/**
 * Decides which headers cross the gateway in each direction (plan section 6, proxy rules).
 */
public final class ProxyHeaders {
    private static final Set<String> HOP_BY_HOP = caseInsensitive(List.of(
            "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Proxy-Connection",
            "TE", "Trailer", "Transfer-Encoding", "Upgrade", "Host", "Content-Length", "Expect"));
    private static final Set<String> NEVER_SENT_TO_BACKEND = caseInsensitive(List.of(
            GatewayHeaders.API_KEY, GatewayHeaders.CLIENT_ID, GatewayHeaders.CORRELATION_ID));
    private static final Set<String> SET_BY_GATEWAY = caseInsensitive(List.of(
            GatewayHeaders.RATE_LIMIT_LIMIT, GatewayHeaders.RATE_LIMIT_REMAINING,
            GatewayHeaders.CORRELATION_ID, GatewayHeaders.SERVED_BY, GatewayHeaders.GATEWAY_ID));

    private ProxyHeaders() {
    }

    /**
     * Returns the headers to send to the backend: the client's headers minus hop-by-hop ones and the API key,
     * with the client ID and correlation ID always set by the gateway so clients cannot spoof them.
     */
    public static HttpHeaders forBackend(HttpServletRequest request, String clientId, String correlationId) {
        Set<String> namedInConnection = connectionTokens(Collections.list(request.getHeaders(HttpHeaders.CONNECTION)));
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (HOP_BY_HOP.contains(name) || NEVER_SENT_TO_BACKEND.contains(name) || namedInConnection.contains(name)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(name, value);
            }
        }
        headers.set(GatewayHeaders.CLIENT_ID, clientId);
        headers.set(GatewayHeaders.CORRELATION_ID, correlationId);
        return headers;
    }

    /**
     * Copies the backend's response headers to the client, except hop-by-hop ones and the headers the gateway
     * sets itself, such as the rate limit headers.
     */
    public static void copyToClient(HttpHeaders backendHeaders, HttpServletResponse response) {
        List<String> connection = backendHeaders.get(HttpHeaders.CONNECTION);
        Set<String> namedInConnection = connectionTokens(connection == null ? List.of() : connection);
        backendHeaders.forEach((name, values) -> {
            if (HOP_BY_HOP.contains(name) || SET_BY_GATEWAY.contains(name) || namedInConnection.contains(name)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }

    private static Set<String> connectionTokens(Collection<String> connectionValues) {
        Set<String> tokens = caseInsensitive(List.of());
        for (String value : connectionValues) {
            for (String token : value.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim());
                }
            }
        }
        return tokens;
    }

    private static Set<String> caseInsensitive(Collection<String> names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(names);
        return set;
    }
}
