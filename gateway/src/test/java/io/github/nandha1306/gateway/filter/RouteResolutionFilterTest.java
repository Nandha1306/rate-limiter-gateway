package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.gateway.config.RouteConfig;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests route resolution inside the running gateway, using the routes from application.yml.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteResolutionFilterTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Verifies that a path no route matches gets 404 with the no_route JSON body and a correlation ID.
     */
    @Test
    void unknownPathReturnsNoRoute() throws Exception {
        mvc.perform(get("/unknown/path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("no_route"))
                .andExpect(header().exists(GatewayHeaders.CORRELATION_ID));
    }

    /**
     * Verifies that a path sharing only part of a segment with a prefix is not routed.
     */
    @Test
    void partialSegmentIsNotRouted() throws Exception {
        mvc.perform(get("/api/ordersXYZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no_route"));
    }

    /**
     * Verifies that a matching path is tagged with its route for later filters, ignoring the query string.
     */
    @Test
    void matchingPathStoresRoute() throws Exception {
        assertThat(routeIdFor(mvc.perform(get("/api/orders/1?expand=items")).andReturn())).isEqualTo("orders");
        assertThat(routeIdFor(mvc.perform(get("/auth/login")).andReturn())).isEqualTo("login");
    }

    /**
     * Verifies that a percent-encoded path is decoded before matching, so encoding cannot dodge a route's limits.
     */
    @Test
    void encodedPathIsDecodedBeforeMatching() throws Exception {
        MvcResult result = mvc.perform(get(URI.create("/api/%6Frders/1"))).andReturn();

        assertThat(routeIdFor(result)).isEqualTo("orders");
    }

    /**
     * Verifies that actuator endpoints bypass routing and are served by the gateway itself.
     */
    @Test
    void actuatorBypassesRouting() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();

        assertThat(result.getRequest().getAttribute(GatewayAttributes.ROUTE)).isNull();
    }

    private static String routeIdFor(MvcResult result) {
        RouteConfig route = (RouteConfig) result.getRequest().getAttribute(GatewayAttributes.ROUTE);
        return route == null ? null : route.id();
    }
}
