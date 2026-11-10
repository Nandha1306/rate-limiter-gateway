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
 * Tests that every response, including errors, says which gateway instance handled it.
 */
@SpringBootTest(properties = "gateway.instance.id=gw-test")
@AutoConfigureMockMvc
class GatewayIdFilterTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Verifies that the configured instance ID is returned on normal, not-found and unauthorized responses alike.
     */
    @Test
    void everyResponseCarriesGatewayId() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(GatewayHeaders.GATEWAY_ID, "gw-test"));

        mvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(GatewayHeaders.GATEWAY_ID, "gw-test"));

        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(GatewayHeaders.GATEWAY_ID, "gw-test"));
    }
}
