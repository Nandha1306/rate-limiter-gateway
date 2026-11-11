package io.github.nandha1306.gateway.limiter;

/**
 * Where rate limit state is kept.
 */
public enum LimiterStore {
    /** Shared in Redis, so limits hold across all gateway instances. */
    REDIS,
    /** In this instance's memory only; for local runs and tests without Redis. */
    MEMORY
}
