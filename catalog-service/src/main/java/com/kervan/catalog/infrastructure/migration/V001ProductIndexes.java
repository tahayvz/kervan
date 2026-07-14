package com.kervan.catalog.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;

/**
 * Mongock migration — {@code products} koleksiyonunun indeksleri.
 * <p>
 * <b>Neden Mongock?</b> "NoSQL'de migration olmaz" bir yanılgıdır. İndeksler ve veri
 * dönüşümleri ortamlar arası tutarlı, versiyonlu, tekrarlanamaz biçimde uygulanmalı —
 * tıpkı Flyway'in Postgres için yaptığı gibi. Mongock bu changeset'i uygulama başlarken
 * <b>bir kez</b> çalıştırır ve kaydını tutar (idempotent).
 * <p>
 * İndeksler:
 * <ul>
 *   <li>{@code uk_products_sku} — benzersiz: aynı SKU iki kez giremez (yarış durumunda
 *       bile veritabanı son güvence)</li>
 *   <li>{@code ix_products_category} — kategoriye göre listeleme hızlı</li>
 *   <li>{@code ix_products_status} — duruma göre filtreleme hızlı</li>
 *   <li>{@code tx_products_text} — ad + açıklama üzerinde tam-metin arama (Faz 5'te
 *       asıl arama Elasticsearch'e taşınacak; bu temel arama için)</li>
 * </ul>
 */
@ChangeUnit(id = "product-indexes-001", order = "001", author = "kervan")
public class V001ProductIndexes {

    @Execution
    public void createIndexes(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps("products");

        indexOps.ensureIndex(new Index()
                .on("sku", Sort.Direction.ASC)
                .unique()
                .named("uk_products_sku"));

        indexOps.ensureIndex(new Index()
                .on("categoryPath", Sort.Direction.ASC)
                .named("ix_products_category"));

        indexOps.ensureIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .named("ix_products_status"));

        indexOps.ensureIndex(new TextIndexDefinition.TextIndexDefinitionBuilder()
                .onField("name")
                .onField("description")
                .named("tx_products_text")
                .build());
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        IndexOperations indexOps = mongoTemplate.indexOps("products");
        indexOps.dropIndex("uk_products_sku");
        indexOps.dropIndex("ix_products_category");
        indexOps.dropIndex("ix_products_status");
        indexOps.dropIndex("tx_products_text");
    }
}
