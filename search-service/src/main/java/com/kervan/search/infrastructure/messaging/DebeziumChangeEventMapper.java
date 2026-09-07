package com.kervan.search.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kervan.search.domain.model.CatalogChange;
import com.kervan.search.domain.model.SearchableProduct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Debezium'un MongoDB değişiklik olayını arama modeline çevirir.
 *
 * <h2>Neden ayrı bir çeviri katmanı?</h2>
 * Bu akış bir domain olayı değil, katalog belgesinin <b>ham kopyasıdır</b> (ADR-0004).
 * Yani sözleşmesi yok: katalogtaki bir alan adı değişirse burası kırılır. Bunu bilerek
 * kabul ediyoruz — ama kırılmanın tek bir yerde olması için çeviri tek sınıfta durur.
 *
 * <h2>Genişletilmiş JSON</h2>
 * MongoDB tipleri JSON'da sarmalanmış gelir: {@code {"$oid": "..."}},
 * {@code {"$numberDecimal": "..."}}, {@code {"$date": ...}}. Üstelik aynı alan,
 * sürücünün yazma biçimine göre sarmalanmış <b>ya da</b> düz gelebilir. Okuyucular
 * bu yüzden iki hâli de kabul eder; tek hâli varsayıp yanılmak, alanın sessizce
 * {@code null} kalmasıyla sonuçlanırdı.
 */
@Component
class DebeziumChangeEventMapper {

    private final ObjectMapper json;

    DebeziumChangeEventMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param key   Kafka mesajının anahtarı — silmede belge kimliğinin tek kaynağı
     * @param value Debezium değişiklik olayı
     * @return uygulanacak değişiklik; olay yok sayılacaksa boş
     */
    Optional<CatalogChange> toChange(String key, String value) {
        JsonNode event = read(value);
        String op = event.path("op").asText("");

        // c=create, r=snapshot okuması, u=update. Hepsinde belgenin tam hâli gelir
        // (capture.mode=change_streams_update_full), o yüzden ayrım gerekmiyor.
        if (op.equals("c") || op.equals("r") || op.equals("u")) {
            JsonNode after = readEmbedded(event.path("after"));
            return after.isMissingNode() || after.isNull()
                    ? Optional.empty()
                    : Optional.of(new CatalogChange.Upserted(toProduct(after)));
        }

        if (op.equals("d")) {
            // Silmede gövde boştur; kimlik yalnızca anahtarda vardır.
            return productIdFromKey(key).map(CatalogChange.Deleted::new);
        }

        return Optional.empty();
    }

    /**
     * Debezium'un MongoDB konektöründe {@code after}, iç içe bir nesne değil
     * <b>JSON metni</b> taşır. İki hâli de kabul ediliyor: konektör sürümü ya da
     * dönüştürücü ayarı değiştiğinde sessizce boş kalmasın.
     */
    private JsonNode readEmbedded(JsonNode node) {
        return node.isTextual() ? read(node.asText()) : node;
    }

    private SearchableProduct toProduct(JsonNode document) {
        return new SearchableProduct(
                objectId(document.path("_id")),
                text(document.path("sku")),
                text(document.path("name")),
                text(document.path("description")),
                text(document.path("brand")),
                text(document.path("categoryPath")),
                decimal(document.path("priceAmount")),
                text(document.path("priceCurrency")),
                text(document.path("status")),
                attributes(document.path("attributes")),
                instant(document.path("updatedAt")),
                version(document.path("version")));
    }

    private static String objectId(JsonNode node) {
        if (node.has("$oid")) {
            return node.get("$oid").asText();
        }
        return node.asText();
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /**
     * Para: Decimal128 olarak {@code {"$numberDecimal": "..."}}, metin olarak, ya da
     * düz sayı olarak gelebilir. Üçü de kabul edilir — {@code double}'a çevrilmez,
     * çünkü kuruş yuvarlanır.
     */
    private static BigDecimal decimal(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.has("$numberDecimal")) {
            return new BigDecimal(node.get("$numberDecimal").asText());
        }
        return new BigDecimal(node.asText());
    }

    private static Instant instant(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode date = node.has("$date") ? node.get("$date") : node;
        if (date.isNumber()) {
            return Instant.ofEpochMilli(date.asLong());
        }
        return Instant.parse(date.asText());
    }

    private static long version(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            // Sürümsüz bir belge sıralanamaz; 0 vererek en eski sayıyoruz. Böyle bir
            // belge normalde olmamalı (Spring Data her yazmada sürümü artırır).
            return 0L;
        }
        JsonNode value = node.has("$numberLong") ? node.get("$numberLong") : node;
        return value.isNumber() ? value.asLong() : Long.parseLong(value.asText());
    }

    private Map<String, Object> attributes(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), scalar(entry.getValue())));
        return attributes;
    }

    /** Öznitelik değerleri gösterim içindir; iç içe yapı düz metne indirgenir. */
    private static Object scalar(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private Optional<String> productIdFromKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        JsonNode id = read(key).path("id");
        if (id.isMissingNode()) {
            return Optional.empty();
        }
        // Anahtar da genişletilmiş JSON taşıyabilir: {"id": "{\"$oid\": \"...\"}"}
        return Optional.of(objectId(readEmbedded(id)));
    }

    private JsonNode read(String raw) {
        try {
            return json.readTree(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Değişiklik olayı okunamadı", e);
        }
    }
}
