package io.github.nandha1306.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Identity of this gateway instance, bound from {@code gateway.instance.*}.
 *
 * @param id name of this instance, returned to clients in {@code X-Gateway-Id}
 */
@ConfigurationProperties("gateway.instance")
public record InstanceProperties(@DefaultValue("gateway-local") String id) {
}
