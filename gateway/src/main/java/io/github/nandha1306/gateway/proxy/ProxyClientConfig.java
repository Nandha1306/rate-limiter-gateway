package io.github.nandha1306.gateway.proxy;

import io.github.nandha1306.gateway.config.GatewayProperties;
import io.github.nandha1306.gateway.config.UpstreamTimeouts;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the HTTP client the gateway uses to call backends.
 */
@Configuration(proxyBeanMethods = false)
public class ProxyClientConfig {

    /**
     * Returns a client on the JDK HttpClient that speaks HTTP/1.1, never follows redirects, and applies the
     * configured connect and read timeouts.
     */
    @Bean
    public RestClient proxyRestClient(GatewayProperties properties) {
        UpstreamTimeouts timeouts = properties.timeouts();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeouts.connect())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeouts.read());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
