package com.kervan.order.infrastructure.metrics;

import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.security.TestJwtSupport;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metrik adlarının sözleşme olduğunu sabitler.
 *
 * <h2>Neden bu test var?</h2>
 * Bir metriğin adı yalnızca bu kodun içinde yaşamaz: Grafana panoları ve alarm
 * kuralları o adı elle yazar. Adı değiştirmek derlemeyi bozmaz, testi bozmaz,
 * uygulamayı bozmaz — yalnızca pano boş kalır ve alarm bir daha hiç çalmaz.
 *
 * <p>Sessiz bozulmanın en kötü türü budur: koruma ortadan kalkar ama ortadan
 * kalktığı görünmez. Ad değişikliği artık burada kırılır.
 *
 * <p>{@link AutoConfigureObservability} gerekli: Spring Boot testlerde metrik dışa
 * aktarımını da varsayılan olarak kapatır, o hâlde Prometheus kayıt defteri hiç
 * oluşmaz ve uç 404 döner.
 */
@AutoConfigureObservability
@TestPropertySource(properties = {
        // 0 = rastgele port. Sabit bir port paralel koşularda çakışırdı.
        "management.server.port=0",
        "management.otlp.tracing.export.enabled=false"
})
@DisplayName("Prometheus ucu")
class PrometheusEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    private String scrape() {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("iş metrikleri beklenen adlarla yayınlanır")
    void exposesBusinessMetricsUnderTheirContractedNames() {
        String body = scrape();

        // Outbox: sayı, en eskinin yaşı, kenara alınanlar. Üçü birlikte
        // "kuyruk akıyor mu" sorusunu cevaplar.
        assertThat(body)
                .contains("kervan_outbox_pending")
                .contains("kervan_outbox_oldest_pending_age_seconds")
                .contains("kervan_outbox_stuck")
                // Saga: durumuna göre havada kalan sipariş sayısı.
                .contains("kervan_saga_in_flight");
    }

    @Test
    @DisplayName("hiç olay olmadan da kayıtlıdırlar")
    void metersExistBeforeAnythingHappens() {
        // Ölçerler ilk olayda değil, açılışta kaydediliyor. Aksi hâlde pano
        // "veri yok" gösterirdi ve bu, "sıfır" ile aynı şey değildir: biri
        // "hiçbir şey olmadı", diğeri "ölçüm çalışmıyor" demektir.
        //
        // Değere değil TYPE satırına bakılıyor. Veritabanı bu modüldeki bütün
        // test sınıflarıyla paylaşılıyor; başka bir sınıfın bıraktığı outbox
        // kaydı sayıyı sıfırdan farklı yapabilir ve testi sırasına bağımlı
        // kılardı. Kanıtlanmak istenen şey zaten sayı değil, ölçerin varlığı.
        assertThat(scrape()).contains("# TYPE kervan_outbox_pending gauge");
    }

    @Test
    @DisplayName("çerçevenin HTTP metrikleri gerçek bir iş isteğini kaydeder")
    void exposesFrameworkHttpMetrics() {
        // RED panoları (hız / hata / süre) bu seriden besleniyor. Kendi
        // metriklerimiz çalışıp bunun kapalı olması, panonun yarısının boş
        // kalması demekti.
        //
        // Trafik GERÇEK bir iş ucuna gönderiliyor. Actuator artık bu portta
        // olmadığı için /actuator/health çağırmak 404 üretirdi ve test, hata
        // yolunun ölçümünü doğrulayıp başarı yolunu hiç görmemiş olurdu.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.tokenFor("customer-" + UUID.randomUUID(), "CUSTOMER"));
        PlaceOrderRequest request = new PlaceOrderRequest("TRY", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 1, new BigDecimal("10.00"))));

        assertThat(rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), OrderResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(scrape())
                .contains("http_server_requests_seconds")
                // Ölçülen şeyin gerçekten o istek olduğunu gösterir.
                .contains("uri=\"/api/v1/orders\"");
    }
}
