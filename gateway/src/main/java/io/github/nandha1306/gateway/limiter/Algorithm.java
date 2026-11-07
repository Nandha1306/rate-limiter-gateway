package io.github.nandha1306.gateway.limiter;

/**
 * Rate limiting algorithms a route can use.
 */
public enum Algorithm {
	TOKEN_BUCKET,
	SLIDING_WINDOW_LOG,
	SLIDING_WINDOW_COUNTER,
	FIXED_WINDOW,
	/** Reads, checks and increments in separate calls, so it over-admits under concurrency. */
	NAIVE_FIXED_WINDOW,
	/** Allows every request without limiting. */
	NONE
}
