package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests how the correlation ID filter picks an ID and exposes it to logs, request attributes and the response.
 */
class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * Verifies that a request without a correlation ID gets a new UUID, used consistently in the response,
     * request attribute and logging context.
     */
    @Test
    void generatesIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idInLogs = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> idInLogs.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        String id = response.getHeader(GatewayHeaders.CORRELATION_ID);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(request.getAttribute(GatewayAttributes.CORRELATION_ID)).isEqualTo(id);
        assertThat(idInLogs.get()).isEqualTo(id);
    }

    /**
     * Verifies that a safe incoming correlation ID is reused unchanged.
     */
    @Test
    void reusesSafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/1");
        request.addHeader(GatewayHeaders.CORRELATION_ID, "client-req_42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(GatewayHeaders.CORRELATION_ID)).isEqualTo("client-req_42");
    }

    /**
     * Verifies that unsafe incoming IDs (log-injection characters, spaces, empty, over 64 characters)
     * are replaced with a new UUID.
     */
    @Test
    void replacesUnsafeIncomingId() {
        assertThat(CorrelationIdFilter.resolve("abc\nFAKE LOG LINE")).isNotEqualTo("abc\nFAKE LOG LINE");
        assertThat(CorrelationIdFilter.resolve("has space")).isNotEqualTo("has space");
        assertThat(CorrelationIdFilter.resolve("")).isNotEmpty();
        assertThat(CorrelationIdFilter.resolve("a".repeat(65))).hasSize(36);
        assertThat(CorrelationIdFilter.resolve("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    /**
     * Verifies that the logging context is cleared after the request, even when the rest of the chain throws.
     */
    @Test
    void clearsLoggingContextAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("backend exploded");
        };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
