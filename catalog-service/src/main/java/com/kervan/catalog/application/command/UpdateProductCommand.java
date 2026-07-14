package com.kervan.catalog.application.command;

import com.kervan.catalog.domain.model.Money;

import java.util.Map;

/**
 * Ürün güncelleme komutu. sku ve durum burada değişmez (sku değişmez iş anahtarı;
 * durum ayrı activate/archive use-case'leriyle yönetilir).
 */
public record UpdateProductCommand(
        String name,
        String description,
        String categoryPath,
        Money price,
        Map<String, Object> attributes
) {
}
