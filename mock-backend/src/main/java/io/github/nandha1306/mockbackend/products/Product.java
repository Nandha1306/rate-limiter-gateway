package io.github.nandha1306.mockbackend.products;

import java.math.BigDecimal;

/**
 * A product in the mock catalogue.
 *
 * @param id    product ID
 * @param name  display name
 * @param price unit price
 */
public record Product(String id, String name, BigDecimal price) {
}
