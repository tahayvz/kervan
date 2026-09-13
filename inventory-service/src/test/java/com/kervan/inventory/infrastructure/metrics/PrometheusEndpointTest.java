package com.kervan.inventory.infrastructure.metrics;

import com.kervan.inventory.AbstractIntegrationTest;
import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.security.TestJwtSupport;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stok metriklerinin adlarını <b>sözleşme</b> olarak sabitler.
 *
 * <h2>Neden bu test var?</h2>
 * Bir metriğin adı yalnızca kodun içinde yaşamaz: Grafana panoları ve alarm kuralları
 * o adı <b>elle yazar</b>. Adı değiştirmek derlemeyi bozmaz, davranış testini bozmaz,
 * uygulamayı bozmaz — yalnızca pano boş kalır ve alarm bir daha hiç çalmaz.
 *
 * <p>Üstelik ad, kodda yazdığımızın aynısı değildir. Micrometer dışa aktarırken onu
 * <b>değiştirir</b>: noktalar alt çizgi olur, {@code DistributionSummary}'ye
 * {@code _count} / {@code _sum} eklenir ve {@code baseUnit} adın içine girer.
 * {@code kervan.stock.received} kodda böyle yazılır, dışarı
 * {@code kervan_stock_received_items_sum} olarak çıkar. Pano koda değil <b>bu çıktıya</b>
 * bakar.
 *
 * <p>Bu depo bu dersi iki kez ödedi: {@code p95} panosu boş kaldı çünkü histogram
 * bucket'ları hiç oluşmamıştı, ve Grafana veri kaynağı {@code uid} verilmediği için
 * panolar boş açıldı. İkisinde de hiçbir hata çıkmadı.
 *
 * <p>{@link AutoConfigureObservability} şart: Spring Boot testlerde metrik dışa
 * aktarımını varsayılan olarak kapatır, o hâlde uç 404 döner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@AutoConfigureObservability
@TestPropertySource(properties = {
        // 0 = rastgele. Sabit yönetim portu (9084) başka bir testle çakışırdı.
        "management.server.port=0",
        "management.otlp.tracing.export.enabled=false"
})
@DisplayName("Prometheus ucu — stok metrikleri")
class PrometheusEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    private HttpEntity<Object> asAdmin(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.tokenFor("yonetici-1", "ADMIN"));
        return new HttpEntity<>(body, headers);
    }

    private String scrape() {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("stok hareketi metrikleri panonun sorguladığı adlarla yayınlanır")
    void stockMovementMetricNamesAreStable() {
        String sku = "SKU-" + UUID.randomUUID();
        rest.postForEntity("/api/v1/stock/" + sku + "/receipts",
                asAdmin(new ReceiveStockRequest("r-" + sku, 10)), String.class);
        rest.postForEntity("/api/v1/stock/" + sku + "/adjustments",
                asAdmin(new AdjustStockRequest("a-" + sku, -1, AdjustmentReason.DAMAGED, null)),
                String.class);

        String scrape = scrape();

        // Bu altı satır, Grafana panosundaki sorguların birebir karşılığıdır.
        // Biri değişirse pano sessizce boşalır; artık burada kırılır.
        assertThat(scrape)
                .contains("kervan_stock_received_items_count")
                .contains("kervan_stock_received_items_sum")
                .contains("kervan_stock_adjusted_items_count")
                .contains("kervan_stock_adjusted_items_sum")
                .contains("reason=\"DAMAGED\"")
                .contains("direction=\"out\"");
    }

    /**
     * Grafana panosundaki sorguları gerçek çıktıya bağlar.
     *
     * <p>Yukarıdaki test adları <b>kodun</b> tarafında sabitliyor. Ama panonun
     * sorguladığı ad ayrı bir dosyada duruyor ve o dosya sessizce kayabilir: kimse
     * hata vermez, panel yalnızca boş kalır. Bu depo o dersi iki kez ödedi.
     *
     * <p>Burada panodan {@code kervan_stock_*} ile başlayan her metrik adı çıkarılıyor
     * ve gerçekten yayınlandığı doğrulanıyor. Yani iki checked-in dosya birbirine
     * bağlanıyor — konektör dosyalarında kullanılan yaklaşımın aynısı.
     */
    @Test
    @DisplayName("panonun sorguladığı stok metrikleri GERÇEKTEN yayınlanıyor")
    void dashboardQueriesMatchPublishedMetrics() throws Exception {
        String sku = "SKU-" + UUID.randomUUID();
        rest.postForEntity("/api/v1/stock/" + sku + "/receipts",
                asAdmin(new ReceiveStockRequest("r2-" + sku, 5)), String.class);
        rest.postForEntity("/api/v1/stock/" + sku + "/adjustments",
                asAdmin(new AdjustStockRequest("a2-" + sku, -1, AdjustmentReason.SHRINKAGE, null)),
                String.class);

        Set<String> queried = stockMetricsQueriedByDashboard();
        // Panoda hiç stok metriği yoksa test anlamsız olurdu; boş küme sessizce geçmesin.
        assertThat(queried).isNotEmpty();

        String scrape = scrape();
        assertThat(queried).allSatisfy(metric -> assertThat(scrape)
                .as("Pano '%s' sorguluyor ama /actuator/prometheus onu yayınlamıyor", metric)
                .contains(metric));
    }

    private static Set<String> stockMetricsQueriedByDashboard() throws Exception {
        Path dashboard = Path.of("..", "infra", "docker", "observability",
                "grafana", "dashboards", "kervan-overview.json").toAbsolutePath().normalize();
        JsonNode root = new ObjectMapper().readTree(Files.readString(dashboard));

        Set<String> names = new LinkedHashSet<>();
        Pattern metric = Pattern.compile("kervan_stock_[a-z0-9_]+");
        for (JsonNode expr : root.findValues("expr")) {
            Matcher matcher = metric.matcher(expr.asText());
            while (matcher.find()) {
                names.add(matcher.group());
            }
        }
        return names;
    }
}
