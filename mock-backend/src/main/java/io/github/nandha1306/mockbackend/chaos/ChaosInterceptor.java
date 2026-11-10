package io.github.nandha1306.mockbackend.chaos;

import io.github.nandha1306.mockbackend.config.MockBackendProperties;
import io.github.nandha1306.mockbackend.web.Delays;
import io.github.nandha1306.mockbackend.web.MockHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the current chaos settings to a mock API call before it reaches its controller.
 */
@Component
public class ChaosInterceptor implements HandlerInterceptor {
    private final ChaosService chaos;
    private final MockBackendProperties properties;

    public ChaosInterceptor(ChaosService chaos, MockBackendProperties properties) {
        this.chaos = chaos;
        this.properties = properties;
    }

    /**
     * Tags the response with this instance's ID, waits the configured latency, and fails the call
     * with 503 at the configured rate.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        response.setHeader(MockHeaders.INSTANCE_ID, properties.instanceId());
        ChaosSettings settings = chaos.current();
        Delays.sleep(Duration.ofMillis(settings.latencyMs()));
        if (settings.shouldFail()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"chaos_failure\"}");
            return false;
        }
        return true;
    }
}
