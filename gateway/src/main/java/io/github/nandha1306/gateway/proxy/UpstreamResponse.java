package io.github.nandha1306.gateway.proxy;

import org.springframework.http.HttpHeaders;

/**
 * A backend's response, fully read so the connection can be released before it is written to the client.
 *
 * @param status   HTTP status code from the backend
 * @param headers  response headers from the backend
 * @param body     response body bytes
 * @param servedBy host and port of the backend that answered
 */
public record UpstreamResponse(int status, HttpHeaders headers, byte[] body, String servedBy) {
}
