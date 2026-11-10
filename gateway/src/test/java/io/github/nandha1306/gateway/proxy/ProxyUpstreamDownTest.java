package io.github.nandha1306.gateway.proxy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.gateway.filter.GatewayHeaders;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests the proxy's answer when nothing is listening at the backend address.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProxyUpstreamDownTest {

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void pointUpstreamAtClosedPort(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("gateway.upstreams.mock.urls[0]", () -> "http://127.0.0.1:" + closedPort);
    }

    /**
     * Verifies that an unreachable backend gets 503 no_healthy_backend with Retry-After: 5, and the request still
     * carries its correlation ID and rate limit headers.
     */
    @Test
    void unreachableBackendReturns503() throws Exception {
        mvc.perform(get("/api/orders/1").header(GatewayHeaders.API_KEY, "demo-premium-key-3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(header().exists(GatewayHeaders.CORRELATION_ID))
                .andExpect(header().exists(GatewayHeaders.RATE_LIMIT_REMAINING))
                .andExpect(jsonPath("$.error").value("no_healthy_backend"));
    }
}
