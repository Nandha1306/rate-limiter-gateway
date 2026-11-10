package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nandha1306.gateway.support.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests rate limiting and the 429 response contract, using test routes that refill one token per hour so no
 * tokens come back during a test. Each test uses its own client, because buckets live as long as the context.
 * Allowed requests are proxied to a stub backend, so they get a deterministic 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ratelimit-test")
class RateLimitFilterTest {
    private static final int OK = 200;
    private static final int TOO_MANY_REQUESTS = 429;
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
     * Verifies that an allowed request carries the bucket capacity and the tokens left.
     */
    @Test
    void allowedRequestCarriesRateLimitHeaders() throws Exception {
        MvcResult result = callLimited("rl-free-a");

        assertThat(result.getResponse().getStatus()).isEqualTo(OK);
        assertThat(result.getResponse().getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isEqualTo("3");
        assertThat(result.getResponse().getHeader(GatewayHeaders.RATE_LIMIT_REMAINING)).isEqualTo("2");
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    /**
     * Verifies that the request after the burst gets 429 with Retry-After, the rate limit headers and the JSON body.
     */
    @Test
    void requestAfterBurstGets429Contract() throws Exception {
        for (int expectedRemaining = 2; expectedRemaining >= 0; expectedRemaining--) {
            MvcResult allowed = callLimited("rl-free-b");
            assertThat(allowed.getResponse().getHeader(GatewayHeaders.RATE_LIMIT_REMAINING))
                    .isEqualTo(String.valueOf(expectedRemaining));
        }

        mvc.perform(get("/api/limited/1").header(GatewayHeaders.API_KEY, "rl-free-b"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"))
                .andExpect(header().string(GatewayHeaders.RATE_LIMIT_LIMIT, "3"))
                .andExpect(header().string(GatewayHeaders.RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().exists(GatewayHeaders.CORRELATION_ID))
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfterMs").value(allOf(
                        greaterThan(3_590_000), lessThanOrEqualTo(3_600_000))));
    }

    /**
     * Verifies that a premium client gets its tier's larger burst.
     */
    @Test
    void premiumTierGetsLargerBurst() throws Exception {
        for (int i = 0; i < 6; i++) {
            MvcResult allowed = callLimited("rl-premium");
            assertThat(allowed.getResponse().getStatus()).isEqualTo(OK);
            assertThat(allowed.getResponse().getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isEqualTo("6");
        }

        assertThat(callLimited("rl-premium").getResponse().getStatus()).isEqualTo(TOO_MANY_REQUESTS);
    }

    /**
     * Verifies that a client whose tier the route does not list is limited by the route's default tier.
     */
    @Test
    void unlistedTierUsesDefaultTier() throws Exception {
        assertThat(callLimited("rl-gold").getResponse().getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isEqualTo("3");
    }

    /**
     * Verifies that one client running out does not block another client of the same tier.
     */
    @Test
    void clientsHaveSeparateBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            callLimited("rl-free-c");
        }
        assertThat(callLimited("rl-free-c").getResponse().getStatus()).isEqualTo(TOO_MANY_REQUESTS);

        assertThat(callLimited("rl-free-d").getResponse().getStatus()).isEqualTo(OK);
    }

    /**
     * Verifies that a public route limits each IP address separately.
     */
    @Test
    void publicRouteLimitsPerIp() throws Exception {
        assertThat(callPublic("198.51.100.1").getResponse().getStatus()).isEqualTo(OK);
        assertThat(callPublic("198.51.100.1").getResponse().getStatus()).isEqualTo(OK);
        assertThat(callPublic("198.51.100.1").getResponse().getStatus()).isEqualTo(TOO_MANY_REQUESTS);

        assertThat(callPublic("198.51.100.2").getResponse().getStatus()).isEqualTo(OK);
    }

    /**
     * Verifies that a route with algorithm NONE is never limited and sends no rate limit headers.
     */
    @Test
    void noneAlgorithmIsNeverLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            MvcResult result = mvc.perform(get("/api/open").header(GatewayHeaders.API_KEY, "rl-free-e")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(OK);
            assertThat(result.getResponse().getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isNull();
        }
    }

    /**
     * Verifies that a request rejected by auth never reaches the limiter, so it gets no rate limit headers.
     */
    @Test
    void rejectedRequestsAreNotRateLimited() throws Exception {
        mvc.perform(get("/api/limited/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(GatewayHeaders.RATE_LIMIT_LIMIT));
    }

    /**
     * Verifies that Retry-After rounds the delay up to whole seconds and is never below 1.
     */
    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        assertThat(RateLimitFilter.retryAfterSeconds(0)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1000)).isEqualTo(1);
        assertThat(RateLimitFilter.retryAfterSeconds(1001)).isEqualTo(2);
        assertThat(RateLimitFilter.retryAfterSeconds(3_599_001)).isEqualTo(3600);
        assertThat(RateLimitFilter.retryAfterSeconds(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE / 1000 + 1);
    }

    private MvcResult callLimited(String apiKey) throws Exception {
        return mvc.perform(get("/api/limited/1").header(GatewayHeaders.API_KEY, apiKey)).andReturn();
    }

    private MvcResult callPublic(String ip) throws Exception {
        return mvc.perform(get("/public").header(GatewayHeaders.REAL_IP, ip)).andReturn();
    }
}
