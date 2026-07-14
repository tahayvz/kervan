package com.kervan.catalog.infrastructure.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data MongoDB deposu — CRUD'u türetir. Bu, altyapı detayıdır; domain
 * bunu görmez, yalnızca {@link ProductRepositoryAdapter} üzerinden kullanılır.
 */
public interface SpringDataProductRepository extends MongoRepository<ProductDocument, String> {

    boolean existsBySku(String sku);
}
