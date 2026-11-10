package io.github.nandha1306.mockbackend.chaos;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Holds the chaos settings currently in effect for this instance.
 */
@Service
public class ChaosService {
    private final AtomicReference<ChaosSettings> settings = new AtomicReference<>(ChaosSettings.NONE);

    /**
     * Returns the settings currently in effect.
     */
    public ChaosSettings current() {
        return settings.get();
    }

    /**
     * Replaces the settings in effect and returns them.
     */
    public ChaosSettings update(ChaosSettings next) {
        settings.set(next);
        return next;
    }

    /**
     * Turns chaos off: no failures and no added latency.
     */
    public void reset() {
        settings.set(ChaosSettings.NONE);
    }
}
