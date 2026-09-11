package com.kervan.catalog.infrastructure.cache;

import com.kervan.catalog.domain.model.Money;
import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.model.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Ürünün önbellekteki hâli.
 *
 * <h2>Neden {@code Product} doğrudan saklanmıyor?</h2>
 * Domain nesnesinin kurucusu özeldir ve nesne yalnızca fabrika metotlarıyla, iş
 * kuralları doğrulanarak üretilir. JSON'dan doğrudan kurulabilmesi için o kapıyı
 * açmak ya da domain'e Jackson anotasyonları koymak gerekirdi — ikisi de kalıcılık
 * ayrıntısını domain'e sızdırırdı.
 *
 * <p>Bunun yerine önbellek kendi temsilini tutar ve okurken domain nesnesini
 * {@code reconstitute} ile geri kurar: geri dönüş yolu, veritabanından okumakla aynı
 * kapıdan geçer.
 */
record CachedProduct(
        String id,
        String sku,
        String name,
        String description,
        String brand,
        String categoryPath,
        BigDecimal priceAmount,
        String priceCurrency,
        ProductStatus status,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    static CachedProduct from(Product product) {
        return new CachedProduct(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getBrand(),
                product.getCategoryPath(),
                product.getPrice().amount(),
                product.getPrice().currency(),
                product.getStatus(),
                product.getAttributes(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getVersion());
    }

    Product toDomain() {
        return Product.reconstitute(id, sku, name, description, brand, categoryPath,
                new Money(priceAmount, priceCurrency),
                status, attributes, createdAt, updatedAt, version);
    }
}
