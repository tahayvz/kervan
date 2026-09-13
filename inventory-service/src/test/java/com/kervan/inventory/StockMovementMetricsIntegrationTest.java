package com.kervan.inventory;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.security.TestJwtSupport;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import com.kervan.inventory.web.dto.StockResponse;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stok hareketlerinin gerçekten ölçüldüğünü doğrular.
 *
 * <p>Bu testlerin varlık sebebi: iş metrikleri sessizce kaybolabilen türden koddur.
 * Bir sarmalayıcının {@code @Primary}'si kalkarsa ya da ölçüm noktası kayarsa uygulama
 * çalışmaya devam eder, testler geçer ve panolar boş kalır. Faz 10'da tam olarak bu
 * yaşanmıştı: iki servisin metrikleri üretiliyor ama dışarı çıkmıyordu.
 *
 * <h2>Neden her iddia FARK üzerinden?</h2>
 * {@link MeterRegistry} Spring bağlamına aittir ve bağlam test sınıfları arasında
 * <b>önbelleklenip paylaşılır</b>: aynı {@code @SpringBootTest} yapılandırmasına sahip
 * her sınıf aynı kayıt defterini, dolayısıyla aynı sayaçları görür. Sayaçlar da
 * sıfırlanmaz.
 *
 * <p>İlk yazılışında iki test mutlak değer iddia ediyordu ({@code count == 1}) ve
 * yerelde geçip CI'da kırıldı — çünkü aynı gerekçeyle düzeltme yapan başka bir test
 * sınıfı vardı ve sıra makineye göre değişti. Mutlak iddia, testi kendi sınıfının
 * dışındaki şeylere bağımlı kılar.
 *
 * <p>Doğrusu: ölçümü önce ve sonra oku, <b>farkı</b> iddia et.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@DisplayName("Stok hareketi metrikleri")
class StockMovementMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MeterRegistry registry;

    private static String admin() {
        return TestJwtSupport.tokenFor("yonetici-1", "ADMIN");
    }

    private <T> HttpEntity<T> withToken(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(admin());
        return new HttpEntity<>(body, headers);
    }

    private String receive(String sku, String receiptId, int quantity) {
        ResponseEntity<StockResponse> response = rest.exchange(
                "/api/v1/stock/" + sku + "/receipts", HttpMethod.POST,
                withToken(new ReceiveStockRequest(receiptId, quantity)), StockResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return sku;
    }

    private ResponseEntity<String> adjust(String sku, AdjustStockRequest req) {
        return rest.exchange("/api/v1/stock/" + sku + "/adjustments", HttpMethod.POST,
                withToken(req), String.class);
    }

    private DistributionSummary received() {
        return registry.find("kervan.stock.received").summary();
    }

    private DistributionSummary adjusted(AdjustmentReason reason, String direction) {
        return registry.find("kervan.stock.adjusted")
                .tag("reason", reason.name()).tag("direction", direction).summary();
    }

    /** Ölçüm henüz hiç kaydedilmemişse seri de yoktur; sıfır say. */
    private long countOf(AdjustmentReason reason, String direction) {
        DistributionSummary summary = adjusted(reason, direction);
        return summary == null ? 0 : summary.count();
    }

    private double sumOf(AdjustmentReason reason, String direction) {
        DistributionSummary summary = adjusted(reason, direction);
        return summary == null ? 0 : summary.totalAmount();
    }

    private String newSku() {
        return "SKU-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("mal kabulü hem adet hem miktar olarak sayılır")
    void receiptsAreMeasured() {
        long countBefore = received() == null ? 0 : received().count();
        double sumBefore = received() == null ? 0 : received().totalAmount();

        receive(newSku(), "r-" + UUID.randomUUID(), 40);

        // İki ayrı soru: "kaç mal kabulü oldu" ve "toplam kaç adet girdi".
        assertThat(received().count()).isEqualTo(countBefore + 1);
        assertThat(received().totalAmount()).isEqualTo(sumBefore + 40);
    }

    @Test
    @DisplayName("TEKRAR gönderilen makbuz sayılmaz")
    void duplicateReceiptIsNotMeasured() {
        String sku = newSku();
        String receiptId = "r-" + UUID.randomUUID();
        receive(sku, receiptId, 40);
        long countAfterFirst = received().count();

        receive(sku, receiptId, 40);

        // İstemcinin yeniden denemesi gerçek bir mal hareketi değildir; sayılsaydı
        // "bu ay ne kadar mal girdi" sorusu ağ aksaklıklarıyla şişerdi.
        assertThat(received().count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("düzeltme gerekçe ve yön etiketleriyle sayılır")
    void adjustmentsAreMeasuredByReasonAndDirection() {
        String sku = receive(newSku(), "r-" + UUID.randomUUID(), 100);
        long countBefore = countOf(AdjustmentReason.SHRINKAGE, "out");
        double sumBefore = sumOf(AdjustmentReason.SHRINKAGE, "out");

        adjust(sku, new AdjustStockRequest("a-" + UUID.randomUUID(), -7,
                AdjustmentReason.SHRINKAGE, null));

        assertThat(adjusted(AdjustmentReason.SHRINKAGE, "out")).isNotNull();
        assertThat(countOf(AdjustmentReason.SHRINKAGE, "out")).isEqualTo(countBefore + 1);
        // Miktar MUTLAK değerle kaydedilir: "yedi adet eksildi" negatif bir toplam
        // değil, yedi adetlik bir harekettir.
        assertThat(sumOf(AdjustmentReason.SHRINKAGE, "out")).isEqualTo(sumBefore + 7);
    }

    @Test
    @DisplayName("artı ve eksi düzeltmeler AYRI serilerde toplanır")
    void directionsAreSeparate() {
        String sku = receive(newSku(), "r-" + UUID.randomUUID(), 100);

        double outBefore = sumOf(AdjustmentReason.COUNT_CORRECTION, "out");
        double inBefore = sumOf(AdjustmentReason.COUNT_CORRECTION, "in");

        adjust(sku, new AdjustStockRequest("a1-" + UUID.randomUUID(), -3,
                AdjustmentReason.COUNT_CORRECTION, null));
        adjust(sku, new AdjustStockRequest("a2-" + UUID.randomUUID(), 5,
                AdjustmentReason.COUNT_CORRECTION, null));

        // Tek seride toplansalardı birbirlerini götürür ve "sayım ne kadar oynuyor"
        // sorusu cevapsız kalırdı. Fark üzerinden: kayıt defteri paylaşılıyor.
        assertThat(sumOf(AdjustmentReason.COUNT_CORRECTION, "out")).isEqualTo(outBefore + 3);
        assertThat(sumOf(AdjustmentReason.COUNT_CORRECTION, "in")).isEqualTo(inBefore + 5);
    }

    @Test
    @DisplayName("REDDEDİLEN düzeltme sayılmaz")
    void rejectedAdjustmentIsNotMeasured() {
        String sku = receive(newSku(), "r-" + UUID.randomUUID(), 3);
        DistributionSummary before = adjusted(AdjustmentReason.DAMAGED, "out");
        long countBefore = before == null ? 0 : before.count();

        // Stoğu eksiye düşürürdü: reddedilmeli.
        assertThat(adjust(sku, new AdjustStockRequest("a-" + UUID.randomUUID(), -5,
                AdjustmentReason.DAMAGED, null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Doğrulama deftere yazmadan ÖNCE yapılıyor; olmayan bir hareket sayılmamalı.
        DistributionSummary after = adjusted(AdjustmentReason.DAMAGED, "out");
        assertThat(after == null ? 0 : after.count()).isEqualTo(countBefore);
    }
}
