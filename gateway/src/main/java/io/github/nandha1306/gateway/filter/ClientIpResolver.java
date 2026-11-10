package io.github.nandha1306.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Works out the client's IP address for per-IP rate limiting on public routes.
 */
public final class ClientIpResolver {
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientIpResolver() {
    }

    /**
     * Returns the address from the {@code X-Real-IP} header set by Nginx when it looks like an IP literal,
     * otherwise the address of the connection itself.
     */
    public static String resolve(HttpServletRequest request) {
        String realIp = request.getHeader(GatewayHeaders.REAL_IP);
        if (realIp != null) {
            String trimmed = realIp.trim();
            if (IP_LITERAL.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return request.getRemoteAddr();
    }
}
