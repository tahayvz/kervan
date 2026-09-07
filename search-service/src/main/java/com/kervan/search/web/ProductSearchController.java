package com.kervan.search.web;

import com.kervan.search.application.ProductSearchService;
import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.web.dto.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Arama API'si.
 *
 * <p>Yalnızca okuma; kimlik doğrulaması yok. Ürün araması katalog listeleme gibi
 * herkese açıktır ve burada kişisel veri dönmez. Yazma uçları hiç yok — bu servis
 * veri üretmez.
 */
@RestController
@RequestMapping("/api/v1/search")
class ProductSearchController {

    private static final int DEFAULT_SIZE = 20;

    private final ProductSearchService search;

    ProductSearchController(ProductSearchService search) {
        this.search = search;
    }

    @GetMapping("/products")
    SearchResponse searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {

        // Doğrulama SearchQuery'nin içinde: sayfa boyutu sınırı ve fiyat aralığı
        // tutarlılığı iş kuralıdır, HTTP katmanının ayrıntısı değil.
        SearchQuery query = new SearchQuery(q, brand, category, minPrice, maxPrice, page, size);

        return SearchResponse.from(search.search(query), page, size);
    }
}
