package com.kervan.catalog.domain.port;

import com.kervan.catalog.domain.model.ProductStatus;

/**
 * Ürün listeleme sorgusu (domain seviyesinde filtre + sayfalama).
 * {@code categoryPath} ve {@code status} null ise o filtre uygulanmaz.
 */
public record ProductQuery(String categoryPath, ProductStatus status, int page, int size) {

    public ProductQuery {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0 || size > 100) {
            size = 20; // makul varsayılan; aşırı büyük sayfa isteklerine karşı koruma
        }
    }
}
