package io.github.nandha1306.mockbackend.demo;

import io.github.nandha1306.mockbackend.config.MockBackendProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cheap endpoint behind the accuracy demo routes, so the test measures the gateway's limiting and nothing else.
 */
@RestController
public class DemoController {
    private final MockBackendProperties properties;

    public DemoController(MockBackendProperties properties) {
        this.properties = properties;
    }

    /**
     * Answers any path under /api/demo with 200 and this instance's ID.
     */
    @GetMapping("/api/demo/**")
    public Map<String, Object> demo() {
        return Map.of("ok", true, "instanceId", properties.instanceId());
    }
}
