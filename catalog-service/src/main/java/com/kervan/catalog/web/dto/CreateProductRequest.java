package com.kervan.catalog.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Ürün oluşturma isteği (HTTP gövdesi). Bean Validation ile giriş doğrulaması burada;
 * geçersiz istekler controller'a bile ulaşmadan 400 döner (bkz. GlobalExceptionHandler).
 */
public record CreateProductRequest(
        @NotBlank(message = "sku zorunludur") String sku,
        @NotBlank(message = "name zorunludur") String name,
        String description,
        @NotBlank(message = "brand zorunludur") String brand,
        @NotBlank(message = "categoryPath zorunludur") String categoryPath,
        @NotNull(message = "price zorunludur") @Valid MoneyDto price,
        Map<String, Object> attributes
) {
}
