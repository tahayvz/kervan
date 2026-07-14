package com.kervan.catalog.application.command;

import com.kervan.catalog.domain.model.Money;

import java.util.Map;

/**
 * Ürün oluşturma komutu (application katmanı girdisi).
 * Web DTO'sundan ayrıdır: web değişse bile use-case sözleşmesi sabit kalır.
 */
public record CreateProductCommand(
        String sku,
        String name,
        String description,
        String brand,
        String categoryPath,
        Money price,
        Map<String, Object> attributes
) {
}
