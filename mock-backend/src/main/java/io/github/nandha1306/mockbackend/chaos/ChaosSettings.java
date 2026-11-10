package io.github.nandha1306.mockbackend.chaos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Failure rate and added latency applied to mock API calls.
 *
 * @param failureRate probability from 0 to 1 that a call returns 503
 * @param latencyMs   milliseconds added to every call, from 0 to {@value #MAX_LATENCY_MS}
 */
public record ChaosSettings(double failureRate, long latencyMs) {
    public static final long MAX_LATENCY_MS = 60_000;
    public static final ChaosSettings NONE = new ChaosSettings(0.0, 0);

    public ChaosSettings {
        if (!(failureRate >= 0.0 && failureRate <= 1.0)) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1, was " + failureRate);
        }
        if (latencyMs < 0 || latencyMs > MAX_LATENCY_MS) {
            throw new IllegalArgumentException(
                    "latencyMs must be between 0 and " + MAX_LATENCY_MS + ", was " + latencyMs);
        }
    }

    /**
     * Randomly decides, at the configured failure rate, whether a call should fail.
     */
    public boolean shouldFail() {
        return failureRate > 0.0 && ThreadLocalRandom.current().nextDouble() < failureRate;
    }
}
