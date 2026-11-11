package io.github.nandha1306.mockbackend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.mockbackend.chaos.ChaosService;
import io.github.nandha1306.mockbackend.web.MockHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests the mock orders, products and login endpoints with chaos turned off.
 */
@SpringBootTest(properties = "mock.instance-id=mock-test")
@AutoConfigureMockMvc
class ApiEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ChaosService chaos;

    @BeforeEach
    void disableChaos() {
        chaos.reset();
    }

    /**
     * Verifies that the first read of an order is a cache MISS and the second is a HIT, tagged with the instance ID.
     */
    @Test
    void orderIsCacheMissFirstThenHit() throws Exception {
        mvc.perform(get("/api/orders/101").header(MockHeaders.CLIENT_ID, "user_1"))
                .andExpect(status().isOk())
                .andExpect(header().string(MockHeaders.CACHE, "MISS"))
                .andExpect(header().string(MockHeaders.INSTANCE_ID, "mock-test"))
                .andExpect(jsonPath("$.id").value("101"))
                .andExpect(jsonPath("$.clientId").value("user_1"))
                .andExpect(jsonPath("$.instanceId").value("mock-test"));

        mvc.perform(get("/api/orders/101").header(MockHeaders.CLIENT_ID, "user_1"))
                .andExpect(status().isOk())
                .andExpect(header().string(MockHeaders.CACHE, "HIT"));
    }

    /**
     * Verifies that the cache is per client, so another client's first read of the same order is a MISS.
     */
    @Test
    void orderCacheIsPerClient() throws Exception {
        mvc.perform(get("/api/orders/202").header(MockHeaders.CLIENT_ID, "user_1"))
                .andExpect(header().string(MockHeaders.CACHE, "MISS"));

        mvc.perform(get("/api/orders/202").header(MockHeaders.CLIENT_ID, "user_2"))
                .andExpect(header().string(MockHeaders.CACHE, "MISS"))
                .andExpect(jsonPath("$.clientId").value("user_2"));
    }

    /**
     * Verifies that a request without a client ID header is served as the anonymous client.
     */
    @Test
    void orderWithoutClientHeaderIsAnonymous() throws Exception {
        mvc.perform(get("/api/orders/303"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("anonymous"));
    }

    /**
     * Verifies that creating an order returns 201 with a location, and reading it back is a cache HIT.
     */
    @Test
    void createOrderReturns201AndIsCached() throws Exception {
        MvcResult created = mvc.perform(post("/api/orders")
                        .header(MockHeaders.CLIENT_ID, "user_3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"book\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.item").value("book"))
                .andExpect(jsonPath("$.clientId").value("user_3"))
                .andReturn();

        String location = created.getResponse().getHeader("Location");

        mvc.perform(get(location).header(MockHeaders.CLIENT_ID, "user_3"))
                .andExpect(status().isOk())
                .andExpect(header().string(MockHeaders.CACHE, "HIT"))
                .andExpect(jsonPath("$.item").value("book"));
    }

    /**
     * Verifies that creating an order without a body uses the default item.
     */
    @Test
    void createOrderWithoutBodyUsesDefaultItem() throws Exception {
        mvc.perform(post("/api/orders").header(MockHeaders.CLIENT_ID, "user_3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item").value("item"));
    }

    /**
     * Verifies that the products endpoint returns the fixed catalogue.
     */
    @Test
    void productsReturnsCatalogue() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string(MockHeaders.INSTANCE_ID, "mock-test"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("p-1"));
    }

    /**
     * Verifies that login returns a non-empty token.
     */
    @Test
    void loginReturnsToken() throws Exception {
        mvc.perform(post("/auth/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Verifies that any path under /api/demo answers 200 with the instance ID, for the accuracy demo routes.
     */
    @Test
    void demoEndpointAnswersAnySubPath() throws Exception {
        for (String path : new String[] {"/api/demo/naive", "/api/demo/lua", "/api/demo/anything/else"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(MockHeaders.INSTANCE_ID, "mock-test"))
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.instanceId").value("mock-test"));
        }
    }
}
