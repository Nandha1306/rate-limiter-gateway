package io.github.nandha1306.gateway.filter;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the gateway's JSON error responses, e.g. {@code {"error":"no_route"}}.
 */
public final class ErrorResponses {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ErrorResponses() {
    }

    /**
     * Writes an error response with the given status and an {@code error} code as the only body field.
     */
    public static void write(HttpServletResponse response, HttpStatus status, String error) throws IOException {
        write(response, status, error, Map.of());
    }

    /**
     * Writes an error response with the given status, an {@code error} code and extra body fields.
     */
    public static void write(HttpServletResponse response, HttpStatus status, String error, Map<String, ?> details)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.putAll(details);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}
