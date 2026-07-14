package com.kervan.catalog.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Ürün güncelleme isteği. sku ve durum burada yer almaz (sku değişmez;
 * durum activate/archive uçlarıyla yönetilir).
 */
public record UpdateProductRequest(
        @NotBlank(message = "name zorunludur") String name,
        String description,
        @NotBlank(message = "categoryPath zorunludur") String categoryPath,
        @NotNull(message = "price zorunludur") @Valid MoneyDto price,
        Map<String, Object> attributes
) {
}
