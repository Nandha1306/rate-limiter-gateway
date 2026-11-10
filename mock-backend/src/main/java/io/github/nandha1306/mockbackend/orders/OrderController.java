package io.github.nandha1306.mockbackend.orders;

import io.github.nandha1306.mockbackend.web.MockHeaders;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock orders API; orders are cached per client, as identified by the gateway's client ID header.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    static final String ANONYMOUS = "anonymous";
    static final String DEFAULT_ITEM = "item";

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Returns the calling client's order and reports in a header whether it was a cache HIT or MISS.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Order> get(
            @PathVariable String id,
            @RequestHeader(value = MockHeaders.CLIENT_ID, defaultValue = ANONYMOUS) String clientId) {
        OrderService.OrderLookup lookup = orders.find(clientId, id);
        return ResponseEntity.ok()
                .header(MockHeaders.CACHE, lookup.cacheHit() ? "HIT" : "MISS")
                .body(lookup.order());
    }

    /**
     * Creates an order for the calling client and returns 201 with its location.
     */
    @PostMapping
    public ResponseEntity<Order> create(
            @RequestHeader(value = MockHeaders.CLIENT_ID, defaultValue = ANONYMOUS) String clientId,
            @RequestBody(required = false) CreateOrderRequest request) {
        String item = request == null || request.item() == null || request.item().isBlank()
                ? DEFAULT_ITEM
                : request.item();
        Order order = orders.create(clientId, item);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    /**
     * Body of a create-order request.
     *
     * @param item item to order; defaults when missing or blank
     */
    public record CreateOrderRequest(String item) {
    }
}
