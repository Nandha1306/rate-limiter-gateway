package io.github.nandha1306.gateway.config;

/**
 * The client an API key belongs to.
 *
 * @param clientId client identity used in rate limit keys and sent to backends
 * @param tier     client's tier, e.g. free or premium
 */
public record ApiKeyConfig(String clientId, String tier) {
}
