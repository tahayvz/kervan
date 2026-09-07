package com.kervan.search.application;

import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.domain.model.SearchResult;
import com.kervan.search.domain.port.ProductIndex;
import org.springframework.stereotype.Service;

/**
 * Arama use-case'i.
 *
 * <p>Bugün ince bir katman: sorguyu indekse iletir. Yine de var, çünkü aramaya
 * eklenecek her kural (eş anlamlılar, kişiselleştirme, gösterilmeyecek ürünler)
 * indeks uygulamasına değil buraya gelir — orası "nasıl aranır"ı bilir, burası "ne
 * aranır"ı.
 */
@Service
public class ProductSearchService {

    private final ProductIndex index;

    public ProductSearchService(ProductIndex index) {
        this.index = index;
    }

    public SearchResult search(SearchQuery query) {
        return index.search(query);
    }
}
