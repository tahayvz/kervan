package com.kervan.catalog.infrastructure.persistence;

import com.kervan.catalog.domain.model.Money;
import com.kervan.catalog.domain.model.Product;
import org.springframework.stereotype.Component;

/**
 * Domain {@link Product} ↔ kalıcılık {@link ProductDocument} çevirici.
 * <p>
 * Bu ayrı katman, domain modelini MongoDB'den yalıtır. Domain değişse de veritabanı
 * şeması aynı kalabilir (ya da tersi); köprü tek yerde, burada.
 */
@Component
public class ProductDocumentMapper {

    public ProductDocument toDocument(Product product) {
        ProductDocument doc = new ProductDocument();
        doc.setId(product.getId());
        doc.setSku(product.getSku());
        doc.setName(product.getName());
        doc.setDescription(product.getDescription());
        doc.setBrand(product.getBrand());
        doc.setCategoryPath(product.getCategoryPath());
        doc.setPriceAmount(product.getPrice().amount());
        doc.setPriceCurrency(product.getPrice().currency());
        doc.setStatus(product.getStatus());
        doc.setAttributes(product.getAttributes());
        doc.setCreatedAt(product.getCreatedAt());
        doc.setUpdatedAt(product.getUpdatedAt());
        doc.setVersion(product.getVersion()); // null → yeni kayıt; dolu → güncelleme (optimistic lock)
        return doc;
    }

    public Product toDomain(ProductDocument doc) {
        return Product.reconstitute(
                doc.getId(),
                doc.getSku(),
                doc.getName(),
                doc.getDescription(),
                doc.getBrand(),
                doc.getCategoryPath(),
                Money.of(doc.getPriceAmount(), doc.getPriceCurrency()),
                doc.getStatus(),
                doc.getAttributes(),
                doc.getCreatedAt(),
                doc.getUpdatedAt(),
                doc.getVersion());
    }
}
