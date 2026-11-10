package io.github.nandha1306.mockbackend.orders;

/**
 * An order as returned by the mock API.
 *
 * @param id         order ID
 * @param clientId   client the order belongs to
 * @param item       ordered item
 * @param instanceId mock backend instance that created it
 */
public record Order(String id, String clientId, String item, String instanceId) {
}
