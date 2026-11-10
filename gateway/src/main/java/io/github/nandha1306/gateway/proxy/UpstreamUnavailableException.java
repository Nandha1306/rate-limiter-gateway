package io.github.nandha1306.gateway.proxy;

/**
 * Thrown when a backend cannot be reached or does not answer within the read timeout.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String upstream, Throwable cause) {
        super("Upstream '" + upstream + "' is unavailable: " + cause.getMessage(), cause);
    }
}
