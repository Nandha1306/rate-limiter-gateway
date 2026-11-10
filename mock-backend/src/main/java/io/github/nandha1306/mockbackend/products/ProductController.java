package io.github.nandha1306.mockbackend.products;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock products API serving a fixed catalogue.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private static final List<Product> CATALOGUE = List.of(
            new Product("p-1", "Keyboard", new BigDecimal("49.99")),
            new Product("p-2", "Mouse", new BigDecimal("19.99")),
            new Product("p-3", "Monitor", new BigDecimal("189.00")));

    /**
     * Returns the full product catalogue.
     */
    @GetMapping
    public List<Product> list() {
        return CATALOGUE;
    }
}
