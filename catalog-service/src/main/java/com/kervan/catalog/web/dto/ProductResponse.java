package com.kervan.catalog.web.dto;

import com.kervan.catalog.domain.model.Product;

import java.time.Instant;
import java.util.Map;

/**
 * Ürün yanıtı (HTTP çıktısı). Domain {@link Product}'ı dış sözleşmeye çevirir;
 * iç modelin (getter isimleri, version alanı vb.) doğrudan sızmasını engeller.
 */
public record ProductResponse(
        String id,
        String sku,
        String name,
        String description,
        String brand,
        String categoryPath,
        MoneyDto price,
        String status,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                p.getBrand(),
                p.getCategoryPath(),
                new MoneyDto(p.getPrice().amount(), p.getPrice().currency()),
                p.getStatus().name(),
                p.getAttributes(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
