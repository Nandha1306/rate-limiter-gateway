package io.github.nandha1306.mockbackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.mockbackend.chaos.ChaosService;
import io.github.nandha1306.mockbackend.web.MockHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tests the chaos endpoint and how its settings affect the mock API.
 */
@SpringBootTest(properties = "mock.instance-id=mock-test")
@AutoConfigureMockMvc
class ChaosTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ChaosService chaos;

    @AfterEach
    void disableChaos() {
        chaos.reset();
    }

    /**
     * Verifies that a failure rate of 1 makes every API call return 503, while the chaos endpoint keeps working.
     */
    @Test
    void fullFailureRateReturns503ButChaosEndpointStillWorks() throws Exception {
        setChaos("{\"failureRate\":1.0,\"latencyMs\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(1.0));

        mvc.perform(get("/api/products"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(MockHeaders.INSTANCE_ID, "mock-test"))
                .andExpect(jsonPath("$.error").value("chaos_failure"));

        mvc.perform(post("/auth/login"))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(get("/chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(1.0));
    }

    /**
     * Verifies that setting the failure rate back to 0 restores normal responses.
     */
    @Test
    void zeroFailureRateRestoresNormalResponses() throws Exception {
        setChaos("{\"failureRate\":1.0}");
        mvc.perform(get("/api/products")).andExpect(status().isServiceUnavailable());

        setChaos("{\"failureRate\":0.0}");
        mvc.perform(get("/api/products")).andExpect(status().isOk());
    }

    /**
     * Verifies that the configured latency delays API responses by at least that long.
     */
    @Test
    void latencyDelaysResponses() throws Exception {
        setChaos("{\"failureRate\":0.0,\"latencyMs\":150}");

        long start = System.nanoTime();
        mvc.perform(get("/api/products")).andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
    }

    /**
     * Verifies that out-of-range or malformed settings are rejected with 400 and leave the current settings unchanged.
     */
    @Test
    void invalidSettingsAreRejected() throws Exception {
        setChaos("{\"failureRate\":1.5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_chaos_settings"));

        setChaos("{\"latencyMs\":-1}")
                .andExpect(status().isBadRequest());

        setChaos("not json")
                .andExpect(status().isBadRequest());

        mvc.perform(get("/chaos"))
                .andExpect(jsonPath("$.failureRate").value(0.0))
                .andExpect(jsonPath("$.latencyMs").value(0));
    }

    private ResultActions setChaos(String json) throws Exception {
        return mvc.perform(post("/chaos").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
