package io.github.nandha1306.gateway.config;

import io.github.nandha1306.gateway.limiter.LimiterStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate limiter settings bound from {@code gateway.limiter.*}.
 *
 * @param store where rate limit state is kept
 */
@ConfigurationProperties("gateway.limiter")
public record LimiterProperties(@DefaultValue("REDIS") LimiterStore store) {
}
