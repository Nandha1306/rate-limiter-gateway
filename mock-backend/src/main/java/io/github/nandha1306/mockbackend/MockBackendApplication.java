package io.github.nandha1306.mockbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the mock upstream service that the gateway proxies to.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockBackendApplication.class, args);
	}
}
