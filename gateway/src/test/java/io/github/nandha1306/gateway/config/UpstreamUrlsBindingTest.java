package io.github.nandha1306.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests that backend URLs can be given as one comma-separated value, as Docker sets them through
 * {@code MOCK_BACKEND_URLS}, instead of indexed properties that would replace the whole list.
 */
class UpstreamUrlsBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayOnly.class);

    /**
     * Verifies that a comma-separated value binds to an ordered list of URLs, ignoring spaces around commas.
     */
    @Test
    void commaSeparatedValueBindsToList() {
        runner.withPropertyValues(
                        "gateway.upstreams.mock.urls=http://backend-2:8081, http://backend-3:8081,http://backend-1:8081")
                .run(context -> assertThat(context.getBean(GatewayProperties.class).upstreams().get("mock").urls())
                        .containsExactly(
                                URI.create("http://backend-2:8081"),
                                URI.create("http://backend-3:8081"),
                                URI.create("http://backend-1:8081")));
    }

    /**
     * Verifies that a single URL still binds to a one-element list.
     */
    @Test
    void singleValueBindsToOneElementList() {
        runner.withPropertyValues("gateway.upstreams.mock.urls=http://localhost:8081")
                .run(context -> assertThat(context.getBean(GatewayProperties.class).upstreams().get("mock").urls())
                        .containsExactly(URI.create("http://localhost:8081")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    static class GatewayOnly {
    }
}
