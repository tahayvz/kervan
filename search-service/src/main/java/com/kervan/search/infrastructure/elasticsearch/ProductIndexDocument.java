package com.kervan.search.infrastructure.elasticsearch;

import com.kervan.search.domain.model.SearchableProduct;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Ürünün Elasticsearch'teki hâli.
 *
 * <h2>Alan tipleri neden elle verilmiş?</h2>
 * Elasticsearch tip çıkarımı yapar ama tahminleri arama davranışını sessizce
 * değiştirir. En kritiği marka ve kategori: {@code text} olsalardı analiz edilir,
 * kelimelere bölünür ve "Nike Air" markası facet listesinde "nike" ve "air" diye iki
 * satır olurdu. {@code keyword} olarak saklanınca bütün hâlleriyle sayılır ve
 * süzülürler.
 *
 * <p>Ad ve açıklama ise tam tersi: onlarda kelime araması isteniyor, o yüzden
 * {@code text}.
 *
 * <h2>Dış sürüm</h2>
 * {@code versionType = EXTERNAL}: sürümü Elasticsearch değil, kaynak belge belirler.
 * Katalogtaki her güncelleme sürümü artırır; indeks yalnızca daha büyük sürümü kabul
 * eder. Böylece tekrar gelen ya da geç kalmış bir olay yeni veriyi ezemez —
 * idempotentlik ve sıra koruması tek mekanizmadan gelir.
 */
@Document(indexName = "products", versionType = Document.VersionType.EXTERNAL)
class ProductIndexDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String sku;

    /** Aranan alan: kelimelere bölünür. */
    @Field(type = FieldType.Text)
    private String name;

    @Field(type = FieldType.Text)
    private String description;

    /** Süzülen ve sayılan alan: bütün hâliyle saklanır. */
    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Keyword)
    private String categoryPath;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    /**
     * Serbest öznitelikler. {@code Object} tipinde saklanır ve indekslenmez:
     * katalogda ne geleceği belli değil ve bilinmeyen alanlar için indeks kurmak,
     * bir ürünün yanlış tipte bir değeri yüzünden bütün yazmanın reddedilmesine yol
     * açardı. Sonuçta gösterilir, üzerinde arama yapılmaz.
     */
    @Field(type = FieldType.Object, enabled = false)
    private Map<String, Object> attributes;

    @Field(type = FieldType.Date)
    private Instant updatedAt;

    @Version
    private Long version;

    ProductIndexDocument() {
        // Spring Data için
    }

    static ProductIndexDocument from(SearchableProduct product) {
        ProductIndexDocument document = new ProductIndexDocument();
        document.id = product.id();
        document.sku = product.sku();
        document.name = product.name();
        document.description = product.description();
        document.brand = product.brand();
        document.categoryPath = product.categoryPath();
        document.price = product.price();
        document.currency = product.currency();
        document.status = product.status();
        document.attributes = product.attributes();
        document.updatedAt = product.updatedAt();
        document.version = product.version();
        return document;
    }

    SearchableProduct toDomain() {
        return new SearchableProduct(id, sku, name, description, brand, categoryPath,
                price, currency, status, attributes, updatedAt,
                version == null ? 0L : version);
    }

    String getBrand() {
        return brand;
    }
}
