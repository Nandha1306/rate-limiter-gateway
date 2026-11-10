package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests client identification inside the running gateway, using the routes and demo keys from application.yml.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFilterTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Verifies that a private route without an API key gets 401 invalid_api_key, still with a correlation ID.
     */
    @Test
    void privateRouteWithoutKeyIsRejected() throws Exception {
        mvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"))
                .andExpect(header().exists(GatewayHeaders.CORRELATION_ID));
    }

    /**
     * Verifies that a private route with an unknown or blank API key gets 401.
     */
    @Test
    void privateRouteWithUnknownKeyIsRejected() throws Exception {
        mvc.perform(get("/api/orders/1").header(GatewayHeaders.API_KEY, "not-a-real-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        mvc.perform(get("/api/products").header(GatewayHeaders.API_KEY, " "))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Verifies that valid free and premium keys identify their clients and tiers.
     */
    @Test
    void validKeyIdentifiesClient() throws Exception {
        assertThat(clientOf(mvc.perform(get("/api/orders/1")
                .header(GatewayHeaders.API_KEY, "demo-free-key-1")).andReturn()))
                .isEqualTo(new ClientIdentity("user_1", "free"));

        assertThat(clientOf(mvc.perform(get("/api/products")
                .header(GatewayHeaders.API_KEY, "demo-premium-key-3")).andReturn()))
                .isEqualTo(new ClientIdentity("user_3", "premium"));
    }

    /**
     * Verifies that a public route needs no key and identifies the caller by IP with the route's default tier.
     */
    @Test
    void publicRouteIdentifiesCallerByIp() throws Exception {
        MvcResult fromSocket = mvc.perform(post("/auth/login")).andReturn();
        assertThat(clientOf(fromSocket)).isEqualTo(new ClientIdentity("ip:127.0.0.1", "anonymous"));

        MvcResult viaNginx = mvc.perform(post("/auth/login").header(GatewayHeaders.REAL_IP, "203.0.113.7")).andReturn();
        assertThat(clientOf(viaNginx)).isEqualTo(new ClientIdentity("ip:203.0.113.7", "anonymous"));
    }

    /**
     * Verifies that an API key sent to a public route is ignored, so the caller is still limited per IP.
     */
    @Test
    void publicRouteIgnoresApiKey() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login").header(GatewayHeaders.API_KEY, "demo-premium-key-3"))
                .andReturn();

        assertThat(clientOf(result)).isEqualTo(new ClientIdentity("ip:127.0.0.1", "anonymous"));
    }

    /**
     * Verifies that unknown paths and actuator are not affected by auth.
     */
    @Test
    void unroutedRequestsSkipAuth() throws Exception {
        mvc.perform(get("/unknown")).andExpect(jsonPath("$.error").value("no_route"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /**
     * Verifies that the client ID is removed from the logging context after the request.
     */
    @Test
    void clientIdIsClearedFromLoggingContext() throws Exception {
        mvc.perform(get("/api/orders/1").header(GatewayHeaders.API_KEY, "demo-free-key-1"));

        assertThat(MDC.get(AuthFilter.MDC_KEY)).isNull();
    }

    private static ClientIdentity clientOf(MvcResult result) {
        return (ClientIdentity) result.getRequest().getAttribute(GatewayAttributes.CLIENT);
    }
}
