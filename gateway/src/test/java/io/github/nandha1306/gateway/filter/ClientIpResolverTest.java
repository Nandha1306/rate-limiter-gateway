package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests how the client IP is chosen for per-IP rate limiting.
 */
class ClientIpResolverTest {

    /**
     * Verifies that the connection's address is used when no X-Real-IP header is present.
     */
    @Test
    void usesConnectionAddressWithoutHeader() {
        assertThat(ClientIpResolver.resolve(request(null))).isEqualTo("10.0.0.9");
    }

    /**
     * Verifies that an IPv4 or IPv6 address in X-Real-IP is used.
     */
    @Test
    void usesRealIpHeader() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.resolve(request(" 2001:db8::1 "))).isEqualTo("2001:db8::1");
    }

    /**
     * Verifies that a header that is not an IP literal is ignored in favour of the connection's address.
     */
    @Test
    void ignoresMalformedRealIpHeader() {
        assertThat(ClientIpResolver.resolve(request("evil.example.com"))).isEqualTo("10.0.0.9");
        assertThat(ClientIpResolver.resolve(request("1.2.3.4, 5.6.7.8"))).isEqualTo("10.0.0.9");
        assertThat(ClientIpResolver.resolve(request(""))).isEqualTo("10.0.0.9");
    }

    private static MockHttpServletRequest request(String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        if (realIp != null) {
            request.addHeader(GatewayHeaders.REAL_IP, realIp);
        }
        return request;
    }
}
