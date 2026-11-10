package io.github.nandha1306.gateway.config;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Stops the gateway from starting when its configuration is invalid.
 */
@Component
public class GatewayConfigCheck implements InitializingBean {
    private final GatewayProperties properties;

    public GatewayConfigCheck(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates the configuration at startup and fails with every error listed.
     */
    @Override
    public void afterPropertiesSet() {
        List<String> errors = GatewayConfigValidator.validate(properties);
        if (!errors.isEmpty()) {
            throw new InvalidGatewayConfigException(errors);
        }
    }
}
