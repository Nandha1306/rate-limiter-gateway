package io.github.nandha1306.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Tests how the backend URL is built from the configured base URL and the client's request.
 */
class ProxyServiceTest {

    /**
     * Verifies that the path and query string are appended to the backend base URL.
     */
    @Test
    void appendsPathAndQuery() {
        assertThat(ProxyService.targetUri(URI.create("http://localhost:8081"), "/api/orders/1", "x=1&y=2"))
                .isEqualTo(URI.create("http://localhost:8081/api/orders/1?x=1&y=2"));
        assertThat(ProxyService.targetUri(URI.create("http://localhost:8081"), "/api/products", null))
                .isEqualTo(URI.create("http://localhost:8081/api/products"));
    }

    /**
     * Verifies that a trailing slash on the base URL does not produce a double slash, and a base path is kept.
     */
    @Test
    void handlesBaseUrlSlashAndPath() {
        assertThat(ProxyService.targetUri(URI.create("http://backend:8081/"), "/api/orders", null))
                .isEqualTo(URI.create("http://backend:8081/api/orders"));
        assertThat(ProxyService.targetUri(URI.create("http://backend:8081/svc"), "/api/orders", null))
                .isEqualTo(URI.create("http://backend:8081/svc/api/orders"));
    }

    /**
     * Verifies that an already-encoded path is forwarded exactly as the client sent it.
     */
    @Test
    void keepsEncodedPathAsSent() {
        assertThat(ProxyService.targetUri(URI.create("http://localhost:8081"), "/api/orders/a%20b", "q=%2F")
                .toString()).isEqualTo("http://localhost:8081/api/orders/a%20b?q=%2F");
    }
}
