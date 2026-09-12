package com.kervan.order.infrastructure.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kervan.order.AbstractIntegrationTest;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log dosyasının biçimini sabitler.
 *
 * <h2>Neden test gerekiyor?</h2>
 * Bu dosyayı insan değil <b>makine</b> okuyor: Alloy satırları ayrıştırıp Loki'ye
 * taşıyor, Grafana da içinden iz kimliğini çıkarıp Jaeger bağlantısı üretiyor.
 * Yani alan adları bir <b>sözleşmedir</b> — tıpkı metrik adları gibi.
 *
 * <p>Biçim bozulursa hiçbir şey hata vermez. Uygulama çalışır, log yazılır,
 * Loki satırı alır. Yalnızca "log'dan ize atla" bağlantısı çalışmaz ve kimse
 * bunu fark etmez. Sessiz bozulma ancak testle yakalanır.
 *
 * <h2>Alan adı neden {@code traceId}, {@code trace.id} değil?</h2>
 * ECS sözlüğü {@code trace.id} derdi. Ama bu alan ECS biçimlendiricisinden değil,
 * Micrometer'ın MDC'sinden geliyor ve MDC anahtarları olduğu gibi aktarılıyor.
 * Adı zorla değiştirmek yerine gerçeği kabul edip Grafana ayarını buna göre
 * yazmak seçildi; önemli olan iki tarafın AYNI adı kullanması.
 */
@AutoConfigureObservability
@TestPropertySource(properties = {
        // Span gönderilecek bir toplayıcı yok; biçim dışa aktarımdan bağımsız.
        "management.otlp.tracing.export.enabled=false"
})
@DisplayName("Yapılandırılmış log")
class StructuredLoggingTest extends AbstractIntegrationTest {

    /**
     * Uygulamanın kendi ayarındaki dosya — testte BAŞKA bir yol verilmiyor.
     *
     * <p>Verilseydi test sırasına bağımlı hâle gelirdi. Spring Boot'ta log
     * yapılandırması bağlam başına değil <b>JVM genelinde</b> yaşar: en son
     * başlayan uygulama bağlamı logback'i yeniden kurar. Bu sınıf dosyayı
     * kendine özel bir yola aldığında, başka bir test sınıfının bağlamı
     * başlayınca dosya eklentisi oraya kayıyor ve buradaki satırlar hiç
     * yazılmıyordu — tek başına yeşil, birlikte kırmızı.
     *
     * <p>Ayrıca test böylece gerçek ayarı doğruluyor: yolu uyduran bir test,
     * uygulamanın nereye yazdığını kanıtlamaz.
     */
    private static final Path LOG_FILE = Path.of("logs/order-service.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Tracer tracer;

    private List<String> logLines() throws IOException {
        return Files.readAllLines(LOG_FILE);
    }

    /** Verilen mesajı taşıyan son log satırını JSON olarak döndürür. */
    private JsonNode lineContaining(String marker) throws IOException {
        String line = logLines().stream()
                .filter(l -> l.contains(marker))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("log satırı bulunamadı: " + marker));
        return JSON.readTree(line);
    }

    @Test
    @DisplayName("her satır tek başına geçerli JSON'dur")
    void everyLineIsStandaloneJson() throws IOException {
        List<String> lines = logLines();
        assertThat(lines).isNotEmpty();

        // Alloy satırları TEK TEK ayrıştırır. Çok satıra yayılan bir kayıt
        // (örneğin biçimlenmemiş bir yığın izi) onun için bozuk satırlardır.
        for (String line : lines) {
            assertThat(catchJsonError(line))
                    .withFailMessage("JSON olmayan log satırı: %s", line)
                    .isNull();
        }
    }

    @Test
    @DisplayName("span içinde yazılan log, izin kimliğini taşır")
    void logWrittenInsideSpanCarriesTheTraceId() throws IOException {
        String marker = "probe-" + UUID.randomUUID();

        Span span = tracer.nextSpan().name("probe").start();
        String expectedTraceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            expectedTraceId = span.context().traceId();
            LoggerFactory.getLogger(StructuredLoggingTest.class).info(marker);
        } finally {
            span.end();
        }

        JsonNode line = lineContaining(marker);

        // Bağlayıcı alan bu. Grafana bu adı arıyor; ad değişirse "log'dan ize
        // atla" bağlantısı sessizce ölür.
        assertThat(line.path("traceId").asText()).isEqualTo(expectedTraceId);
        assertThat(line.path("spanId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("satır, toplayıcının etiket üreteceği alanları içerir")
    void carriesFieldsTheCollectorTurnsIntoLabels() throws IOException {
        JsonNode line = lineContaining("\"log.level\"");

        // Alloy bu ikisini Loki etiketi yapıyor. Etiket kümeleri SONLU:
        // servis adı ve seviye. İz kimliği bilerek etiket DEĞİL — her istek yeni
        // bir değer üretir ve sınırsız etiket, Loki'yi metrik tarafındaki
        // kardinalite sorununun aynısına sokar.
        assertThat(line.path("service.name").asText()).isEqualTo("order-service");
        assertThat(line.path("log.level").asText()).isNotBlank();
        assertThat(line.path("@timestamp").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Grafana'nın aradığı desen, uygulamanın yazdığı satıra uyar")
    void grafanaDerivedFieldMatchesWhatTheApplicationWrites() throws IOException {
        String marker = "derived-" + UUID.randomUUID();

        Span span = tracer.nextSpan().name("derived").start();
        String expectedTraceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            expectedTraceId = span.context().traceId();
            LoggerFactory.getLogger(StructuredLoggingTest.class).info(marker);
        } finally {
            span.end();
        }

        String logLine = logLines().stream()
                .filter(l -> l.contains(marker))
                .reduce((first, second) -> second)
                .orElseThrow();

        Matcher matcher = Pattern.compile(grafanaTraceIdRegexFromRepository()).matcher(logLine);

        // İki AYRI dosya arasındaki sözleşme: biri Java, diğeri Grafana ayarı.
        // Derleyici ikisini birbirine bağlamaz. Log alan adı değişirse hiçbir
        // şey hata vermez; yalnızca "log'dan ize atla" bağlantısı ölür ve bunu
        // kimse fark etmez. Test bağı kuran tek şey.
        assertThat(matcher.find())
                .withFailMessage("Grafana'nın türetilmiş alan deseni log satirina uymuyor.\n"
                        + "desen: %s\nsatir: %s", grafanaTraceIdRegexFromRepository(), logLine)
                .isTrue();
        assertThat(matcher.group(1)).isEqualTo(expectedTraceId);
    }

    /** Depodaki GERÇEK Grafana ayarından deseni okur; testte kopyasını tutmaz. */
    @SuppressWarnings("unchecked")
    private static String grafanaTraceIdRegexFromRepository() throws IOException {
        Path config = Path.of("../infra/docker/observability/grafana/provisioning/datasources/datasources.yml");
        assertThat(config).as("Grafana veri kaynağı ayarı bulunamadı").exists();

        Map<String, Object> root;
        try (var in = Files.newInputStream(config)) {
            root = new Yaml().load(in);
        }

        for (Map<String, Object> ds : (List<Map<String, Object>>) root.get("datasources")) {
            if (!"loki".equals(ds.get("type"))) {
                continue;
            }
            Map<String, Object> jsonData = (Map<String, Object>) ds.get("jsonData");
            List<Map<String, Object>> derived =
                    (List<Map<String, Object>>) jsonData.get("derivedFields");
            return (String) derived.get(0).get("matcherRegex");
        }
        throw new AssertionError("Loki veri kaynağında türetilmiş alan tanımlı değil");
    }

    private static String catchJsonError(String line) {
        try {
            JSON.readTree(line);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
