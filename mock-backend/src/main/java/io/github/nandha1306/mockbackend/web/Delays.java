package io.github.nandha1306.mockbackend.web;

import java.time.Duration;

/**
 * Blocking pauses used to simulate slow work.
 */
public final class Delays {

    private Delays() {
    }

    /**
     * Sleeps for the given duration; on interrupt, stops early and keeps the thread's interrupt flag set.
     */
    public static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
