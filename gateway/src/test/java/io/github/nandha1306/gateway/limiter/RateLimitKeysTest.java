package io.github.nandha1306.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests the rate limit key format from plan section 4.
 */
class RateLimitKeysTest {

    /**
     * Verifies that route, tier and client sit inside the hash tag braces, followed by the algorithm code.
     */
    @Test
    void buildsHashTaggedKey() {
        assertThat(RateLimitKeys.build("orders", "premium", "user_42", "tb"))
                .isEqualTo("rl:{orders:premium:user_42}:tb");
        assertThat(RateLimitKeys.build("login", "anonymous", "ip:172.18.0.1", "swl"))
                .isEqualTo("rl:{login:anonymous:ip:172.18.0.1}:swl");
    }
}
