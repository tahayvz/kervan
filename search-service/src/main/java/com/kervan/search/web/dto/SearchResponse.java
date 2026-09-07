package com.kervan.search.web.dto;

import com.kervan.search.domain.model.SearchResult;
import com.kervan.search.domain.model.SearchableProduct;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Arama cevabı.
 *
 * <p>Domain modeli doğrudan döndürülmüyor: dışarıya verilen şekil bir sözleşmedir ve
 * iç model değiştiğinde istemcilerin kırılmaması gerekir.
 */
public record SearchResponse(
        List<Item> items,
        long total,
        int page,
        int size,
        Facets facets) {

    public record Item(
            String id,
            String sku,
            String name,
            String description,
            String brand,
            String categoryPath,
            BigDecimal price,
            String currency,
            String status,
            Map<String, Object> attributes) {
    }

    /** "Bu sonuçlar içinde hangi markadan kaç tane var" sayaçları. */
    public record Facets(Map<String, Long> brands, Map<String, Long> categories) {
    }

    public static SearchResponse from(SearchResult result, int page, int size) {
        return new SearchResponse(
                result.items().stream().map(SearchResponse::toItem).toList(),
                result.total(),
                page,
                size,
                new Facets(result.brandFacets(), result.categoryFacets()));
    }

    private static Item toItem(SearchableProduct product) {
        return new Item(product.id(), product.sku(), product.name(), product.description(),
                product.brand(), product.categoryPath(), product.price(), product.currency(),
                product.status(), product.attributes());
    }
}
