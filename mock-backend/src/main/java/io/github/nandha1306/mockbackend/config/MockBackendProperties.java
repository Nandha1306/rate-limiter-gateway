package io.github.nandha1306.mockbackend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of one mock backend instance.
 *
 * @param instanceId     name of this replica, returned in every API response
 * @param cacheMissDelay simulated database time for an order cache miss
 */
@ConfigurationProperties("mock")
public record MockBackendProperties(
        @DefaultValue("mock-local") String instanceId,
        @DefaultValue("20ms") Duration cacheMissDelay) {
}
