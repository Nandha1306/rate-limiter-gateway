package io.github.nandha1306.mockbackend.orders;

import io.github.nandha1306.mockbackend.config.MockBackendProperties;
import io.github.nandha1306.mockbackend.web.Delays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Serves orders from a per-client in-memory cache, simulating a slow database on a miss.
 */
@Service
public class OrderService {
    private final ConcurrentHashMap<CacheKey, Order> cache = new ConcurrentHashMap<>();
    private final MockBackendProperties properties;

    public OrderService(MockBackendProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the client's order from the cache; on a miss, waits the simulated database delay,
     * then builds and caches it.
     */
    public OrderLookup find(String clientId, String orderId) {
        CacheKey key = new CacheKey(clientId, orderId);
        Order cached = cache.get(key);
        if (cached != null) {
            return new OrderLookup(cached, true);
        }
        Delays.sleep(properties.cacheMissDelay());
        Order loaded = new Order(orderId, clientId, "item-" + orderId, properties.instanceId());
        Order winner = cache.putIfAbsent(key, loaded);
        return new OrderLookup(winner != null ? winner : loaded, false);
    }

    /**
     * Creates a new order for the client and caches it.
     */
    public Order create(String clientId, String item) {
        Order order = new Order(UUID.randomUUID().toString(), clientId, item, properties.instanceId());
        cache.put(new CacheKey(clientId, order.id()), order);
        return order;
    }

    /**
     * An order together with whether it was served from the cache.
     */
    public record OrderLookup(Order order, boolean cacheHit) {
    }

    private record CacheKey(String clientId, String orderId) {
    }
}
