package io.github.nandha1306.mockbackend.chaos;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads and changes this instance's chaos settings. Called directly on the backend, never through the gateway.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {
    private final ChaosService chaos;

    public ChaosController(ChaosService chaos) {
        this.chaos = chaos;
    }

    /**
     * Returns the settings currently in effect.
     */
    @GetMapping
    public ChaosSettings current() {
        return chaos.current();
    }

    /**
     * Replaces the settings in effect; omitted fields default to 0.
     */
    @PostMapping
    public ChaosSettings update(@RequestBody ChaosSettings settings) {
        return chaos.update(settings);
    }

    /**
     * Rejects malformed or out-of-range settings with 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_chaos_settings",
                "message", String.valueOf(e.getMostSpecificCause().getMessage())));
    }
}
