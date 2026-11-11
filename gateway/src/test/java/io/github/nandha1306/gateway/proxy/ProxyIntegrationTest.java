package io.github.nandha1306.gateway.proxy;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.gateway.filter.GatewayHeaders;
import io.github.nandha1306.gateway.support.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the proxy end to end against a real HTTP stub backend: what the gateway sends, what it returns, and how
 * it handles backend errors and timeouts.
 */
@SpringBootTest(properties = "gateway.timeouts.read=300ms")
@AutoConfigureMockMvc
class ProxyIntegrationTest {
    private static final String PREMIUM_KEY = "demo-premium-key-3";
    private static final StubBackend BACKEND = StubBackend.start();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void pointUpstreamAtStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.mock.urls[0]", BACKEND::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        BACKEND.stop();
    }

    /**
     * Verifies that the method, raw path, query string, body and content type reach the backend unchanged.
     */
    @Test
    void forwardsMethodPathQueryAndBody() throws Exception {
        mvc.perform(post("/api/orders?x=1&y=two")
                        .header(GatewayHeaders.API_KEY, PREMIUM_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"book\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.uri").value("/api/orders?x=1&y=two"))
                .andExpect(jsonPath("$.body").value("{\"item\":\"book\"}"))
                .andExpect(jsonPath("$.headers['content-type'][0]").value(startsWith("application/json")));
    }

    /**
     * Verifies that the API key never reaches the backend and a client-supplied client ID is replaced.
     */
    @Test
    void stripsApiKeyAndOverwritesClientId() throws Exception {
        mvc.perform(get("/api/orders/7")
                        .header(GatewayHeaders.API_KEY, PREMIUM_KEY)
                        .header(GatewayHeaders.CLIENT_ID, "spoofed_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers['x-api-key']").doesNotExist())
                .andExpect(jsonPath("$.headers['x-client-id'][0]").value("user_3"))
                .andExpect(jsonPath("$.headers['x-client-id'].length()").value(1));
    }

    /**
     * Verifies that the correlation ID is forwarded to the backend and returned to the client.
     */
    @Test
    void forwardsCorrelationId() throws Exception {
        mvc.perform(get("/api/orders/7")
                        .header(GatewayHeaders.API_KEY, PREMIUM_KEY)
                        .header(GatewayHeaders.CORRELATION_ID, "trace-77"))
                .andExpect(jsonPath("$.headers['x-correlation-id'][0]").value("trace-77"))
                .andExpect(header().string(GatewayHeaders.CORRELATION_ID, "trace-77"));
    }

    /**
     * Verifies that hop-by-hop headers, and headers the client lists in Connection, are not forwarded.
     */
    @Test
    void stripsHopByHopRequestHeaders() throws Exception {
        mvc.perform(get("/api/orders/7")
                        .header(GatewayHeaders.API_KEY, PREMIUM_KEY)
                        .header(HttpHeaders.CONNECTION, "X-Secret")
                        .header("X-Secret", "internal")
                        .header(HttpHeaders.PROXY_AUTHORIZATION, "Basic abc")
                        .header("Keep-Alive", "300")
                        .header("X-Custom", "kept"))
                .andExpect(jsonPath("$.headers['x-secret']").doesNotExist())
                .andExpect(jsonPath("$.headers['proxy-authorization']").doesNotExist())
                .andExpect(jsonPath("$.headers['keep-alive']").doesNotExist())
                .andExpect(jsonPath("$.headers['x-custom'][0]").value("kept"));
    }

    /**
     * Verifies that backend response headers reach the client, except hop-by-hop ones and the gateway's own
     * rate limit headers, and that the response says which backend served it.
     */
    @Test
    void copiesBackendResponseHeadersExceptGatewayOwned() throws Exception {
        mvc.perform(get("/api/orders/7").header(GatewayHeaders.API_KEY, PREMIUM_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Backend", "stub"))
                .andExpect(header().string("X-Instance-Id", "stub-1"))
                .andExpect(header().string(GatewayHeaders.SERVED_BY, BACKEND.authority()))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, "a=1", "b=2"))
                .andExpect(header().doesNotExist("Keep-Alive"))
                .andExpect(header().string(GatewayHeaders.RATE_LIMIT_REMAINING, not("999")))
                .andExpect(header().string(GatewayHeaders.GATEWAY_ID, "gateway-local"))
                .andExpect(header().string(GatewayHeaders.RATE_LIMIT_LIMIT, "100"));
    }

    /**
     * Verifies that a 5xx from the backend is passed through as-is rather than replaced by a gateway error.
     */
    @Test
    void backendServerErrorIsPassedThrough() throws Exception {
        mvc.perform(get("/api/products").header(GatewayHeaders.API_KEY, PREMIUM_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("chaos_failure"))
                .andExpect(header().string(GatewayHeaders.SERVED_BY, BACKEND.authority()))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    /**
     * Verifies that a backend slower than the read timeout gets 503 no_healthy_backend with Retry-After: 5.
     */
    @Test
    void slowBackendReturns503() throws Exception {
        mvc.perform(get("/api/orders/slow").header(GatewayHeaders.API_KEY, PREMIUM_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.error").value("no_healthy_backend"));
    }
}
