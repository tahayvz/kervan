package com.kervan.search.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Arama sonucu ve yanındaki sayaçlar.
 *
 * <p><b>Facet nedir?</b> "Bu sonuçlar içinde hangi markadan kaç tane var" bilgisidir.
 * Kullanıcı arayüzündeki "Nike (12), Adidas (7)" listesi budur. Ayrı sorgularla
 * hesaplansaydı hem yavaş olurdu hem de süzgeçlerle tutarsız kalabilirdi; aynı
 * sorguda hesaplanır.
 *
 * @param total eşleşen toplam kayıt — sayfadaki değil, hepsi
 */
public record SearchResult(
        List<SearchableProduct> items,
        long total,
        Map<String, Long> brandFacets,
        Map<String, Long> categoryFacets) {

    public SearchResult {
        items = List.copyOf(items);
        brandFacets = Map.copyOf(brandFacets);
        categoryFacets = Map.copyOf(categoryFacets);
    }
}
