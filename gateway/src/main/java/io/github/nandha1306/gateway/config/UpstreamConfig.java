package io.github.nandha1306.gateway.config;

import java.net.URI;
import java.util.List;

/**
 * A named pool of backend instances that routes proxy to.
 *
 * @param urls base URL of each backend instance
 */
public record UpstreamConfig(List<URI> urls) {

    public UpstreamConfig {
        urls = urls == null ? List.of() : List.copyOf(urls);
    }
}
