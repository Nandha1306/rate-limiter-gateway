package io.github.nandha1306.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nandha1306.gateway.config.ApiKeyConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests API key lookup.
 */
class ApiKeyRegistryTest {
    private final ApiKeyRegistry registry = new ApiKeyRegistry(Map.of(
            "key-free", new ApiKeyConfig("user_1", "free"),
            "key-premium", new ApiKeyConfig("user_3", "premium")));

    /**
     * Verifies that a known key returns its client and tier.
     */
    @Test
    void knownKeyReturnsClient() {
        assertThat(registry.lookup("key-free")).contains(new ClientIdentity("user_1", "free"));
        assertThat(registry.lookup("key-premium")).contains(new ClientIdentity("user_3", "premium"));
    }

    /**
     * Verifies that unknown, blank and missing keys, and keys differing only in case, return no client.
     */
    @Test
    void unknownBlankOrMissingKeyReturnsNothing() {
        assertThat(registry.lookup("key-unknown")).isEmpty();
        assertThat(registry.lookup("KEY-FREE")).isEmpty();
        assertThat(registry.lookup("  ")).isEmpty();
        assertThat(registry.lookup(null)).isEmpty();
    }
}
