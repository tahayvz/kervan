package com.kervan.catalog.infrastructure.cache;

import com.kervan.catalog.AbstractMongoIntegrationTest;
import com.kervan.catalog.domain.model.Money;
import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.port.ProductRepository;
import com.kervan.catalog.infrastructure.persistence.ProductRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Önbelleğin gerçekten okumayı kestiğini ve yazmada temizlendiğini doğrular.
 *
 * <p>Sahte bir önbellekle test etmek serileştirmeyi ve TTL'i hiç sınamazdı; hatanın
 * çıkacağı yer tam olarak orası. Burada gerçek Redis var.
 */
@DisplayName("Ürün önbelleği")
class ProductCacheIntegrationTest extends AbstractMongoIntegrationTest {

    /** Sarmalayıcı — uygulamanın gördüğü port. */
    @Autowired
    private ProductRepository repository;

    /** Sarmalanan gerçek uygulama; önbelleği atlayarak veritabanına bakmak için. */
    @Autowired
    private ProductRepositoryAdapter database;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void reset() {
        mongo.getCollection("products").drop();
        cacheManager.getCache("products").clear();
    }

    private Product saveProduct(String sku) {
        return repository.save(Product.create(sku, "Kablosuz kulaklık", "Açıklama", "Nike",
                "elektronik/ses", Money.of(new BigDecimal("1299.90"), "TRY"),
                Map.of("renk", "siyah")));
    }

    @Test
    @DisplayName("ilk okuma veritabanından gelir, ikincisi önbellekten")
    void secondReadComesFromCache() {
        String id = saveProduct("SKU-1").getId();
        repository.findById(id);

        // Veritabanındaki kaydı önbelleğin arkasından değiştiriyoruz. Önbellek
        // gerçekten kullanılıyorsa okuma hâlâ ESKİ değeri döner; kullanılmıyorsa
        // yeni değer gelir ve test "önbellek çalışmıyor" der.
        mongo.getCollection("products").updateMany(
                new org.bson.Document(), new org.bson.Document("$set",
                        new org.bson.Document("name", "Arkadan değiştirildi")));

        assertThat(repository.findById(id)).get()
                .extracting(Product::getName).isEqualTo("Kablosuz kulaklık");
    }

    @Test
    @DisplayName("yazma önbelleği temizler")
    void writeEvictsTheEntry() {
        Product product = saveProduct("SKU-1");
        repository.findById(product.getId());

        product.updateDetails("Yeni ad", "Açıklama", "elektronik/ses", Map.of());
        repository.save(product);

        // Temizlenmeseydi eski ad dönerdi: müşteri, değiştirdiği ürünü eski hâliyle
        // görürdü.
        assertThat(repository.findById(product.getId())).get()
                .extracting(Product::getName).isEqualTo("Yeni ad");
    }

    @Test
    @DisplayName("silme önbelleği temizler")
    void deleteEvictsTheEntry() {
        String id = saveProduct("SKU-1").getId();
        repository.findById(id);

        repository.deleteById(id);

        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("önbellekten dönen ürün veritabanındakiyle aynı")
    void cachedProductSurvivesSerialisation() {
        String id = saveProduct("SKU-1").getId();

        Product first = repository.findById(id).orElseThrow();
        Product cached = repository.findById(id).orElseThrow();

        // JSON'a gidip geri gelen nesne aynı olmalı. Para, zaman ve öznitelikler
        // serileştirmede en kolay bozulan alanlar.
        assertThat(cached.getSku()).isEqualTo(first.getSku());
        assertThat(cached.getPrice().amount()).isEqualByComparingTo(first.getPrice().amount());
        assertThat(cached.getPrice().currency()).isEqualTo(first.getPrice().currency());
        assertThat(cached.getStatus()).isEqualTo(first.getStatus());
        assertThat(cached.getAttributes()).isEqualTo(first.getAttributes());
        assertThat(cached.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(cached.getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    @DisplayName("bulunamayan ürün önbelleğe yazılmaz")
    void missingProductIsNotCached() {
        String id = "65f000000000000000000099";

        assertThat(repository.findById(id)).isEmpty();

        // Yokluk önbelleklenseydi, ürün sonradan eklendiğinde bir süre daha
        // "yok" görünürdü.
        assertThat(cacheManager.getCache("products").get(id)).isNull();
    }

    @Test
    @DisplayName("arama önbelleklenmez")
    void searchIsNotCached() {
        saveProduct("SKU-1");

        var first = repository.search(new com.kervan.catalog.domain.port.ProductQuery(
                null, null, 0, 10));
        saveProduct("SKU-2");
        var second = repository.search(new com.kervan.catalog.domain.port.ProductQuery(
                null, null, 0, 10));

        // Sorgu uzayı geniş ve hangi sonucun bayatladığını bilmek mümkün değil;
        // önbelleklenseydi yeni ürün listede görünmezdi.
        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).hasSize(2);
    }
}
