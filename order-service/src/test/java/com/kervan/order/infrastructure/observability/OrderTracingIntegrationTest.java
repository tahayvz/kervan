package com.kervan.order.infrastructure.observability;

import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.security.TestJwtSupport;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İsteğin izleme (trace) bağlamının outbox satırına yazıldığını doğrular.
 *
 * <h2>Ne kanıtlanıyor?</h2>
 * İstemcinin gönderdiği {@code traceparent} başlığıyla, veritabanına düşen outbox
 * satırındaki {@code trace_parent} sütununun <b>aynı ize</b> ait olduğu. Bu bağ
 * kopsaydı, Debezium olayı yayınladığında tüketici yeni bir iz başlatır ve bir
 * siparişin yolculuğu Jaeger'da parçalara ayrılırdı (ADR-0013).
 *
 * <h2>İki ayar neden gerekli?</h2>
 * <ul>
 *   <li>{@link AutoConfigureObservability}: Spring Boot testlerde izlemeyi
 *       <b>varsayılan olarak kapatır</b>. Bu anotasyon olmadan test yeşil kalıp
 *       hiçbir şey ölçmezdi — sütun boş olurdu ve sebebi görünmezdi.</li>
 *   <li>Outbox yayıncısı kapalı: açık olsaydı kayıt saniyeler içinde gönderilip
 *       işaretlenebilir, testin okumak istediği satır değişmiş olurdu. Burada
 *       ölçülen şey yayınlama değil, <b>yazma anında</b> bağlamın yakalanmasıdır.</li>
 * </ul>
 */
@AutoConfigureObservability
@TestPropertySource(properties = {
        "kervan.outbox.publisher.enabled=false",
        // Span gönderilecek bir toplayıcı yok; dışa aktarımı kapatmak her testin
        // sonunda başarısız bağlantı denemesi üretmesini önler. Bağlamın
        // YAYILMASI dışa aktarımdan bağımsızdır.
        "management.otlp.tracing.export.enabled=false"
})
@DisplayName("İzleme bağlamı outbox satırına yazılır")
class OrderTracingIntegrationTest extends AbstractIntegrationTest {

    /** W3C belgesindeki örnek iz kimliği; biçimi sabit olduğu için sabit yazıldı. */
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_SPAN_ID = "00f067aa0ba902b7";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("gelen traceparent, outbox satırında aynı iz kimliğiyle devam eder")
    void carriesIncomingTraceIntoOutboxRow() {
        String orderId = placeOrder();

        // Bir siparişi almak outbox'a İKİ satır yazar: olay (OrderPlaced) ve
        // saga'nın ilk komutu (stok ayır). İkisi de aynı transaction'da yazılır,
        // ikisi de aynı izi taşımalı — biri taşımazsa zincir o dalda kopardı.
        List<String> stored = jdbc.queryForList(
                "SELECT trace_parent FROM outbox_messages WHERE aggregate_id = ? ORDER BY occurred_at",
                String.class, orderId);

        assertThat(stored)
                .withFailMessage("siparişin outbox kayıtları bulunamadı")
                .hasSizeGreaterThanOrEqualTo(2)
                .doesNotContainNull();

        assertThat(stored).allSatisfy(traceParent -> {
            String[] parts = traceParent.split("-");
            assertThat(parts).hasSize(4);

            // AYNI iz kimliği: zincir kopmadı.
            assertThat(parts[1]).isEqualTo(INCOMING_TRACE_ID);

            // FARKLI span kimliği: sunucu, gelen adımın çocuğu olan yeni bir adım
            // açtı. Aynı olsaydı bağlam kopyalanmış ama yeni adım hiç
            // oluşmamış olurdu.
            assertThat(parts[2]).isNotEqualTo(INCOMING_SPAN_ID).matches("[0-9a-f]{16}");

            // Örnekleme kararı da taşınır: gelen istek "bu izi kaydedin" dedi.
            assertThat(parts[3]).isEqualTo("01");
        });
    }

    private String placeOrder() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.tokenFor("customer-" + UUID.randomUUID(), "CUSTOMER"));
        headers.set("traceparent", "00-" + INCOMING_TRACE_ID + "-" + INCOMING_SPAN_ID + "-01");

        PlaceOrderRequest request = new PlaceOrderRequest("TRY", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 2, new BigDecimal("100.00"))));

        ResponseEntity<OrderResponse> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().id();
    }
}
