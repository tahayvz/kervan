package com.kervan.search.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kervan.search.domain.model.CatalogChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Değişiklik olayının okunması.
 *
 * <p>Bu akışın yazılı bir sözleşmesi yok: Debezium'un ürettiği ham kayıt, MongoDB
 * tiplerini genişletilmiş JSON içinde sarmalayarak taşır ve aynı alan sürücünün
 * yazma biçimine göre sarmalanmış ya da düz gelebilir. Tek hâli varsayıp yanılmanın
 * bedeli, alanın sessizce boş kalmasıdır — hata vermez, arama sonucu eksik çıkar.
 */
@DisplayName("DebeziumChangeEventMapper")
class DebeziumChangeEventMapperTest {

    private final DebeziumChangeEventMapper mapper =
            new DebeziumChangeEventMapper(new ObjectMapper());

    private static final String KEY = "{\"id\":\"{\\\"$oid\\\": \\\"65f000000000000000000001\\\"}\"}";

    /** {@code after} bir JSON metni olarak gelir; içindeki tipler sarmalanmıştır. */
    private static String insertEvent(String documentJson) {
        return """
                {"before":null,"after":%s,"op":"c","ts_ms":1788000000000,
                 "source":{"db":"catalog","collection":"products"}}
                """.formatted(new ObjectMapper().valueToTree(documentJson).toString());
    }

    @Test
    @DisplayName("eklenen ürün okunur")
    void readsInsertedProduct() {
        String document = """
                {"_id":{"$oid":"65f000000000000000000001"},
                 "sku":"SKU-1","name":"Kablosuz kulaklık","description":"Gürültü engelleyici",
                 "brand":"Nike","categoryPath":"elektronik/ses",
                 "priceAmount":{"$numberDecimal":"1299.90"},"priceCurrency":"TRY",
                 "status":"ACTIVE","attributes":{"renk":"siyah","garantiYil":2},
                 "updatedAt":{"$date":1788000000000},"version":{"$numberLong":"3"}}
                """;

        CatalogChange change = mapper.toChange(KEY, insertEvent(document)).orElseThrow();

        assertThat(change).isInstanceOf(CatalogChange.Upserted.class);
        var product = ((CatalogChange.Upserted) change).product();
        assertThat(product.id()).isEqualTo("65f000000000000000000001");
        assertThat(product.sku()).isEqualTo("SKU-1");
        assertThat(product.brand()).isEqualTo("Nike");
        assertThat(product.price()).isEqualByComparingTo("1299.90");
        assertThat(product.updatedAt()).isEqualTo(Instant.ofEpochMilli(1788000000000L));
        assertThat(product.version()).isEqualTo(3L);
        assertThat(product.attributes()).containsEntry("renk", "siyah");
    }

    @Test
    @DisplayName("sarmalanmamış tipler de okunur")
    void readsPlainTypesToo() {
        // Aynı alanlar, sürücü ayarına göre düz gelebilir. İkisi de kabul edilmezse
        // fiyat ve sürüm sessizce kaybolurdu.
        String document = """
                {"_id":"65f000000000000000000001","sku":"SKU-1","name":"Ürün",
                 "priceAmount":"249.90","priceCurrency":"TRY","status":"ACTIVE",
                 "updatedAt":"2026-03-01T10:15:30Z","version":7}
                """;

        var product = ((CatalogChange.Upserted)
                mapper.toChange(KEY, insertEvent(document)).orElseThrow()).product();

        assertThat(product.id()).isEqualTo("65f000000000000000000001");
        assertThat(product.price()).isEqualByComparingTo("249.90");
        assertThat(product.updatedAt()).isEqualTo(Instant.parse("2026-03-01T10:15:30Z"));
        assertThat(product.version()).isEqualTo(7L);
    }

    @Test
    @DisplayName("fiyat BigDecimal olarak okunur, double'a çevrilmez")
    void keepsPriceExact() {
        String document = """
                {"_id":"1","sku":"SKU-1","priceAmount":{"$numberDecimal":"0.1"},"version":1}
                """;

        var product = ((CatalogChange.Upserted)
                mapper.toChange(KEY, insertEvent(document)).orElseThrow()).product();

        // double üzerinden geçseydi 0.1 tam olarak temsil edilemezdi.
        assertThat(product.price()).isEqualTo(new BigDecimal("0.1"));
    }

    @Test
    @DisplayName("silme olayında kimlik anahtardan alınır")
    void readsDeleteFromKey() {
        String event = """
                {"before":null,"after":null,"op":"d","ts_ms":1788000000000}
                """;

        CatalogChange change = mapper.toChange(KEY, event).orElseThrow();

        // Silmede gövde boştur; kimliğin tek kaynağı anahtardır.
        assertThat(change).isInstanceOf(CatalogChange.Deleted.class);
        assertThat(change.productId()).isEqualTo("65f000000000000000000001");
    }

    @Test
    @DisplayName("anlaşılmayan olay tipi yok sayılır")
    void ignoresUnknownOperations() {
        String event = """
                {"op":"m","ts_ms":1788000000000}
                """;

        // Debezium ileride yeni bir işlem tipi ekleyebilir. Yok saymak, o mesajın
        // kuyruğu kilitlemesinden iyidir; kaybolan bir şey de yok, çünkü ürünün son
        // hâli bir sonraki güncellemede yine gelir.
        assertThat(mapper.toChange(KEY, event)).isEmpty();
    }

    @Test
    @DisplayName("sürümü olmayan belge en eski sayılır")
    void treatsMissingVersionAsOldest() {
        String document = """
                {"_id":"1","sku":"SKU-1","name":"Ürün"}
                """;

        var product = ((CatalogChange.Upserted)
                mapper.toChange(KEY, insertEvent(document)).orElseThrow()).product();

        assertThat(product.version()).isZero();
    }
}
