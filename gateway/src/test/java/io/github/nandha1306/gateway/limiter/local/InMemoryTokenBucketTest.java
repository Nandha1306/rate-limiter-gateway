package io.github.nandha1306.gateway.limiter.local;

import io.github.nandha1306.gateway.limiter.LimitSpec;
import io.github.nandha1306.gateway.limiter.RateLimitResult;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;


/**
 * Tests the in-memory token bucket using a fake clock that only moves when a test advances it.
 */
class InMemoryTokenBucketTest {

    /**
     * Verifies that a new bucket starts full and allows a request, leaving capacity minus one token.
     */
    @Test
    void shouldAllowRequestWhenTokensAreAvailable() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(10, 10, 10);

        RateLimitResult result = limiter.tryAcquire("user-101", spec);

        assertTrue(result.allowed());
        assertEquals(9, result.remaining());
        assertEquals(0, result.retryAfterMs());
    }


    /**
     * Verifies that a request is denied once the bucket is empty, with a retry-after of one refill interval.
     */
    @Test
    void shouldRejectRequestWhenBucketIsEmpty() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(1, 1, 1);

        RateLimitResult first = limiter.tryAcquire("user-101", spec);
        RateLimitResult second = limiter.tryAcquire("user-101", spec);

        assertTrue(first.allowed());
        assertFalse(second.allowed());
        assertEquals(0, second.remaining());
        assertEquals(1000, second.retryAfterMs());
    }


    /**
     * Verifies that an empty bucket gains tokens at the refill rate as time passes.
     */
    @Test
    void shouldRefillTokensAfterTimePasses() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(2, 2, 2);

        limiter.tryAcquire("user-101", spec);
        limiter.tryAcquire("user-101", spec);

        clock.addAndGet(1_000_000_000L);

        RateLimitResult result = limiter.tryAcquire("user-101", spec);

        assertTrue(result.allowed());
        assertEquals(0, result.remaining());
        assertEquals(0, result.retryAfterMs());
    }


    /**
     * Verifies that each key has its own bucket, so one client running out does not block another.
     */
    @Test
    void shouldMaintainSeparateBucketsForDifferentUsers() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(1, 1, 1);

        RateLimitResult user1First = limiter.tryAcquire("user-101", spec);
        RateLimitResult user1Second = limiter.tryAcquire("user-101", spec);
        RateLimitResult user2First = limiter.tryAcquire("user-202", spec);

        assertTrue(user1First.allowed());
        assertFalse(user1Second.allowed());
        assertTrue(user2First.allowed());
        assertEquals(0, user2First.remaining());
    }

    /**
     * Verifies that retry-after equals the time needed to refill one token at a slow refill rate.
     */
    @Test
    void shouldCalculateRetryAfterCorrectly() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(2, 4, 1);

        limiter.tryAcquire("user-101", spec);

        RateLimitResult result = limiter.tryAcquire("user-101", spec);

        assertFalse(result.allowed());
        assertEquals(0, result.remaining());
        assertEquals(2000, result.retryAfterMs());
    }


    /**
     * Verifies that a burst larger than the sustained limit is allowed up to the bucket capacity, and no further.
     */
    @Test
    void shouldAllowBurstUpToCapacity() {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(2, 2, 5);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = limiter.tryAcquire("user-101", spec);
            assertTrue(result.allowed());
        }

        RateLimitResult sixth = limiter.tryAcquire("user-101", spec);

        assertFalse(sixth.allowed());
        assertEquals(0, sixth.remaining());
    }


    /**
     * Verifies that 100 threads making 1000 attempts on one key are allowed exactly the bucket capacity of 100.
     */
    @RepeatedTest(20)
    void shouldAllowExactlyCapacityRequestsUnderConcurrency() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        InMemoryTokenBucket limiter = new InMemoryTokenBucket(clock::get);
        LimitSpec spec = new LimitSpec(1, Long.MAX_VALUE, 100);

        int threadCount = 100;
        int attemptsPerThread = 10;

        AtomicInteger allowedCount = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();

                    for (int j = 0; j < attemptsPerThread; j++) {
                        RateLimitResult result =
                                limiter.tryAcquire("user-101", spec);

                        if (result.allowed()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }

            assertEquals(100, allowedCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

}
