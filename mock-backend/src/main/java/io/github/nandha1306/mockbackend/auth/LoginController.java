package io.github.nandha1306.mockbackend.auth;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock login API used to exercise per-IP rate limiting on a public route.
 */
@RestController
public class LoginController {

    /**
     * Accepts any login and returns a random token.
     */
    @PostMapping("/auth/login")
    public Map<String, String> login() {
        return Map.of("token", UUID.randomUUID().toString());
    }
}
