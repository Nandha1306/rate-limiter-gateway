package io.github.nandha1306.gateway.limiter;

/**
 * How a route handles requests when Redis is unavailable.
 */
public enum FailureMode {
	/** Allows every request. */
	FAIL_OPEN,
	/** Blocks every request. */
	FAIL_CLOSED,
	/** Limits with a per-instance in-memory token bucket. */
	LOCAL_FALLBACK
}
