package io.github.nandha1306.gateway.limiter;

/**
 * Limit for one tier of one route.
 *
 * @param limit         requests allowed per window
 * @param windowSeconds window length in seconds
 * @param burst         token bucket capacity; 0 means the same as limit
 */
public record LimitSpec(long limit, long windowSeconds, long burst) {

	public LimitSpec {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0, was " + limit);
		}
		if (windowSeconds <= 0) {
			throw new IllegalArgumentException("windowSeconds must be > 0, was " + windowSeconds);
		}
		if (burst < 0) {
			throw new IllegalArgumentException("burst must be >= 0, was " + burst);
		}
	}

	/** Returns the token bucket capacity: burst, or limit when burst is 0. */
	public long capacity() {
		return burst == 0 ? limit : burst;
	}

	/** Returns the token bucket refill rate in tokens per second. */
	public double refillPerSecond() {
		return (double) limit / windowSeconds;
	}
}
