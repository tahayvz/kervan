package com.kervan.catalog.web.dto;

import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.port.PageResult;

import java.util.List;

/**
 * Sayfalı liste yanıtı. Domain {@link PageResult}'ı ProductResponse'lara çevirir.
 */
public record PageResponse(
        List<ProductResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static PageResponse from(PageResult<Product> result) {
        List<ProductResponse> items = result.items().stream()
                .map(ProductResponse::from)
                .toList();
        return new PageResponse(items, result.page(), result.size(),
                result.totalElements(), result.totalPages());
    }
}
