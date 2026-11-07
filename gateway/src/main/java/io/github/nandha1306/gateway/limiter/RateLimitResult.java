package io.github.nandha1306.gateway.limiter;

/**
 * Outcome of one rate limit check.
 *
 * @param allowed      whether the request may proceed
 * @param remaining    whole permits left after this request
 * @param retryAfterMs milliseconds to wait before a retry can succeed
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs) {

	public RateLimitResult {
		if (remaining < 0) {
			throw new IllegalArgumentException("remaining must be >= 0, was " + remaining);
		}
		if (retryAfterMs < 0) {
			throw new IllegalArgumentException("retryAfterMs must be >= 0, was " + retryAfterMs);
		}
	}
}
