package com.kervan.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bir Debezium konektör dosyasının {@code config} bölümü.
 *
 * <p>Hem {@link OutboxBridge} hem {@link OutboxConnectorConfigTest} buradan okur, ve
 * bu bilinçli: köprü, Debezium'un okuduğu <b>aynı dosyayı</b> uygulasın diye.
 *
 * <p>Önceden köprü kuralları elle kopyalanmıştı ve bu, taklidin klasik zayıflığıydı:
 * taklit, taklit ettiği şeyle birlikte güncellenmez. Konektör dosyasında konu adı
 * değişse köprü eski adı kullanmaya devam eder, test yine yeşil kalır ve dosyadaki
 * hata görülmezdi. Artık tek kaynak var.
 */
record ConnectorConfig(String name, Map<String, String> values) {

    /** Konektör dosyalarının deponun kökündeki yeri. */
    static final Path DIRECTORY = Path.of("..", "infra", "docker", "debezium");

    static ConnectorConfig load(String fileName) {
        Path file = DIRECTORY.resolve(fileName).toAbsolutePath().normalize();
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file));
            JsonNode config = root.path("config");
            if (config.isMissingNode()) {
                throw new IllegalStateException("Konektör dosyasında 'config' yok: " + file);
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Iterator<String> it = config.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                values.put(key, config.get(key).asText());
            }
            return new ConnectorConfig(root.path("name").asText(fileName), values);
        } catch (IOException e) {
            // Acik hata: dosya tasinirsa test "beklenen X, gelen null" gibi anlamsiz
            // bir sekilde degil, nerede aradigini soyleyerek kirilsin.
            throw new IllegalStateException("Konektör dosyası okunamadı: " + file, e);
        }
    }

    String get(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Konektör ayarı yok: " + key + " (" + name + ")");
        }
        return value;
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    /** Hedef konu — yalnızca sabit konuya yönlendiren konektörler için anlamlı. */
    String fixedTopic() {
        return get("transforms.outbox.route.topic.replacement");
    }

    String keyColumn() {
        return get("transforms.outbox.table.field.event.key");
    }

    String payloadColumn() {
        return get("transforms.outbox.table.field.event.payload");
    }

    String idColumn() {
        return get("transforms.outbox.table.field.event.id");
    }

    /**
     * {@code trace_parent:header:traceparent} biçimindeki eşlemeden sütun adını çıkarır.
     * Bu satır izleme bağlamının servisler arasında taşınmasını sağlıyor (ADR-0013).
     */
    String traceParentColumn() {
        String placement = get("transforms.outbox.table.fields.additional.placement");
        return placement.split(":", 2)[0];
    }

    String traceParentHeader() {
        String[] parts = get("transforms.outbox.table.fields.additional.placement").split(":");
        return parts[parts.length - 1];
    }
}
