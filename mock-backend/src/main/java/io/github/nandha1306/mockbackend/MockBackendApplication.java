package io.github.nandha1306.mockbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the mock upstream service that the gateway proxies to.
 */
@SpringBootApplication
public class MockBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockBackendApplication.class, args);
	}
}
