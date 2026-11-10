package io.github.nandha1306.gateway.filter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests that the correlation ID filter is registered in the running gateway and applies to every path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Verifies that even actuator responses carry a correlation ID.
     */
    @Test
    void actuatorResponseHasCorrelationId() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(GatewayHeaders.CORRELATION_ID));
    }

    /**
     * Verifies that a caller-supplied correlation ID comes back unchanged.
     */
    @Test
    void callerSuppliedIdIsEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header(GatewayHeaders.CORRELATION_ID, "trace-123"))
                .andExpect(header().string(GatewayHeaders.CORRELATION_ID, "trace-123"));
    }
}
