package io.github.nandha1306.mockbackend.web;

import io.github.nandha1306.mockbackend.chaos.ChaosInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC setup for the mock backend.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ChaosInterceptor chaosInterceptor;

    public WebConfig(ChaosInterceptor chaosInterceptor) {
        this.chaosInterceptor = chaosInterceptor;
    }

    /**
     * Applies chaos to the mock API and login endpoints, but not to the chaos endpoint itself.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chaosInterceptor).addPathPatterns("/api/**", "/auth/**");
    }
}
