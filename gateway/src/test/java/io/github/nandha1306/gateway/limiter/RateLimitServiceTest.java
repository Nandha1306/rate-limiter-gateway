package io.github.nandha1306.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.limiter.local.InMemoryTokenBucket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests how the rate limit service picks a limiter and key code for each store and algorithm, and separates buckets.
 */
class RateLimitServiceTest {
    private static final LimitSpec TWO_PER_HOUR = new LimitSpec(1, 3600, 2);

    private final List<String> redisBucketKeys = new ArrayList<>();
    private final List<String> naiveKeys = new ArrayList<>();
    private final RateLimiter recordingRedisBucket = recording(redisBucketKeys);
    private final RateLimiter recordingNaive = recording(naiveKeys);

    private final RateLimitService memoryService =
            new RateLimitService(LimiterStore.MEMORY, new InMemoryTokenBucket(), recordingRedisBucket, recordingNaive);
    private final RateLimitService redisService =
            new RateLimitService(LimiterStore.REDIS, new InMemoryTokenBucket(), recordingRedisBucket, recordingNaive);

    /**
     * Verifies that algorithm NONE never limits and never touches a limiter.
     */
    @Test
    void noneAlwaysAllows() {
        for (int i = 0; i < 50; i++) {
            assertThat(redisService.check(request("open", "free", "user_1", Algorithm.NONE)).allowed()).isTrue();
        }
        assertThat(redisBucketKeys).isEmpty();
        assertThat(naiveKeys).isEmpty();
    }

    /**
     * Verifies that the Redis store sends the naive algorithm to the naive limiter with its own key code.
     */
    @Test
    void redisStoreSendsNaiveToNaiveLimiter() {
        redisService.check(request("demo-naive", "free", "user_1", Algorithm.NAIVE_FIXED_WINDOW));

        assertThat(naiveKeys).containsExactly("rl:{demo-naive:free:user_1}:nfw");
        assertThat(redisBucketKeys).isEmpty();
    }

    /**
     * Verifies that the Redis store sends every other algorithm to the Lua token bucket, keyed with the token bucket
     * code because that is the limiter actually running.
     */
    @Test
    void redisStoreSendsOtherAlgorithmsToLuaTokenBucket() {
        redisService.check(request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET));
        redisService.check(request("products", "free", "user_1", Algorithm.SLIDING_WINDOW_COUNTER));
        redisService.check(request("login", "anonymous", "ip:10.0.0.1", Algorithm.SLIDING_WINDOW_LOG));
        redisService.check(request("reports", "free", "user_1", Algorithm.FIXED_WINDOW));

        assertThat(redisBucketKeys).containsExactly(
                "rl:{orders:free:user_1}:tb",
                "rl:{products:free:user_1}:tb",
                "rl:{login:anonymous:ip:10.0.0.1}:tb",
                "rl:{reports:free:user_1}:tb");
        assertThat(naiveKeys).isEmpty();
    }

    /**
     * Verifies that the memory store keeps every algorithm, including naive, in the in-memory bucket and never calls
     * Redis.
     */
    @Test
    void memoryStoreNeverCallsRedis() {
        memoryService.check(request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET));
        memoryService.check(request("demo-naive", "free", "user_1", Algorithm.NAIVE_FIXED_WINDOW));

        assertThat(redisBucketKeys).isEmpty();
        assertThat(naiveKeys).isEmpty();
    }

    /**
     * Verifies that limited algorithms allow up to the bucket capacity and then deny.
     */
    @Test
    void limitedAlgorithmEnforcesCapacity() {
        RateLimitRequest request = request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET);

        assertThat(memoryService.check(request).allowed()).isTrue();
        assertThat(memoryService.check(request).allowed()).isTrue();
        assertThat(memoryService.check(request).allowed()).isFalse();
    }

    /**
     * Verifies that each route, tier and client combination has its own bucket.
     */
    @Test
    void bucketsAreSeparatedByRouteTierAndClient() {
        RateLimitRequest exhausted = request("orders", "free", "user_1", Algorithm.TOKEN_BUCKET);
        memoryService.check(exhausted);
        memoryService.check(exhausted);
        assertThat(memoryService.check(exhausted).allowed()).isFalse();

        assertThat(memoryService.check(request("orders", "free", "user_2", Algorithm.TOKEN_BUCKET)).allowed()).isTrue();
        assertThat(memoryService.check(request("products", "free", "user_1", Algorithm.TOKEN_BUCKET)).allowed())
                .isTrue();
        assertThat(memoryService.check(request("orders", "premium", "user_1", Algorithm.TOKEN_BUCKET)).allowed())
                .isTrue();
    }

    private static RateLimiter recording(List<String> keys) {
        return (key, spec) -> {
            keys.add(key);
            return new RateLimitResult(true, 0, 0);
        };
    }

    private static RateLimitRequest request(String routeId, String tier, String clientId, Algorithm algorithm) {
        return new RateLimitRequest(routeId, tier, clientId, algorithm, TWO_PER_HOUR);
    }
}
