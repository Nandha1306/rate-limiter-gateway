package io.github.nandha1306.gateway.config;

import java.util.List;

/**
 * Thrown when the gateway configuration breaks one or more validation rules.
 */
public class InvalidGatewayConfigException extends RuntimeException {
    private final List<String> errors;

    public InvalidGatewayConfigException(List<String> errors) {
        super("Invalid gateway configuration:" + System.lineSeparator() + " - "
                + String.join(System.lineSeparator() + " - ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * Returns every validation error found.
     */
    public List<String> errors() {
        return errors;
    }
}
