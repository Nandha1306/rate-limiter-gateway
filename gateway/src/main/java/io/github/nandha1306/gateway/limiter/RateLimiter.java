package io.github.nandha1306.gateway.limiter;

/**
 * Decides whether a request identified by a key may proceed under a limit.
 */
public interface RateLimiter {

	/**
	 * Tries to take one permit for the given key.
	 *
	 * @param key  rate limit key of the client and route
	 * @param spec limit to enforce for this key
	 * @return whether the request is allowed, the remaining quota and the retry-after time
	 */
	RateLimitResult tryAcquire(String key, LimitSpec spec);
}
