package io.github.nandha1306.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Time limits for calls from the gateway to backends.
 *
 * @param connect maximum time to open a connection
 * @param read    maximum time to wait for a response
 */
public record UpstreamTimeouts(
        @DefaultValue("500ms") Duration connect,
        @DefaultValue("2s") Duration read) {
}
