package io.github.nandha1306.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests how the rate limit service picks a limiter and separates buckets.
 */
class RateLimitServiceTest {
    private static final LimitSpec TWO_PER_HOUR = new LimitSpec(1, 3600, 2);

    private final RateLimitService service = new RateLimitService();

    /**
     * Verifies that algorithm NONE never limits.
     */
    @Test
    void noneAlwaysAllows() {
        for (int i = 0; i < 50; i++) {
            assertThat(service.check(request("open", "free", "user_1", Algorithm.NONE)).allowed()).isTrue();
        }
    }

    /**
     * Verifies that limited algorithms allow up to the bucket capacity and then deny.
     */
    @Test
    void limitedAlgorithmEnforcesCapacity() {
        RateLimitRequest request = request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET);

        assertThat(service.check(request).allowed()).isTrue();
        assertThat(service.check(request).allowed()).isTrue();
        assertThat(service.check(request).allowed()).isFalse();
    }

    /**
     * Verifies that algorithms not built yet are still enforced with the token bucket rather than left open.
     */
    @Test
    void unbuiltAlgorithmsFallBackToTokenBucket() {
        RateLimitRequest request = request("products", "free", "user_1", Algorithm.SLIDING_WINDOW_COUNTER);

        service.check(request);
        service.check(request);
        assertThat(service.check(request).allowed()).isFalse();
    }

    /**
     * Verifies that each route, tier and client combination has its own bucket.
     */
    @Test
    void bucketsAreSeparatedByRouteTierAndClient() {
        RateLimitRequest exhausted = request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET);
        service.check(exhausted);
        service.check(exhausted);
        assertThat(service.check(exhausted).allowed()).isFalse();

        assertThat(service.check(request("orders", "free", "user_2", Algorithm.TOKEN_BUCKET)).allowed()).isTrue();
        assertThat(service.check(request("products", "free", "user_1", Algorithm.TOKEN_BUCKET)).allowed()).isTrue();
        assertThat(service.check(request("orders", "premium", "user_1", Algorithm.TOKEN_BUCKET)).allowed()).isTrue();
    }

    /**
     * Verifies that the limiter is called with the plan's key format and the token bucket code.
     */
    @Test
    void usesTokenBucketKeyFormat() {
        List<String> keys = new ArrayList<>();
        RateLimitService recording = new RateLimitService((key, spec) -> {
            keys.add(key);
            return new RateLimitResult(true, 0, 0);
        });

        recording.check(request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET));

        assertThat(keys).containsExactly("rl:{orders:free:user_1}:tb");
    }

    private static RateLimitRequest request(String routeId, String tier, String clientId, Algorithm algorithm) {
        return new RateLimitRequest(routeId, tier, clientId, algorithm, TWO_PER_HOUR);
    }
}
