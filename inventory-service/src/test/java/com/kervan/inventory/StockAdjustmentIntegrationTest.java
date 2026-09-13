package com.kervan.inventory;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.security.TestJwtSupport;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import com.kervan.inventory.web.dto.StockAdjustmentResponse;
import com.kervan.inventory.web.dto.StockResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayım düzeltmesinin gerçek veritabanına karşı davranışı (ADR-0021).
 *
 * <p>Birim testleri deponun sözleşmesini taklit eder; burada sınanan şey o sözleşmenin
 * gerçekten tuttuğu: {@code ON CONFLICT DO NOTHING} ile idempotentlik, denetim izinin
 * yazılıp geri okunabilmesi, ve düzeltmeyi yapanın kimliğinin <b>token'dan</b> gelmesi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@DisplayName("Stok düzeltme ucu")
class StockAdjustmentIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_SUBJECT = "yonetici-sub-1";

    @Autowired
    private TestRestTemplate rest;

    private static String admin() {
        return TestJwtSupport.tokenFor(ADMIN_SUBJECT, "ADMIN");
    }

    private <T> HttpEntity<T> withToken(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    /** Düzeltilebilecek bir stok yaratır (mal kabulüyle, SQL ile değil). */
    private String stockedSku(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        ResponseEntity<StockResponse> created = rest.exchange(
                "/api/v1/stock/" + sku + "/receipts", HttpMethod.POST,
                withToken(admin(), new ReceiveStockRequest("r-" + sku, quantity)),
                StockResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return sku;
    }

    private ResponseEntity<StockResponse> adjust(String sku, AdjustStockRequest req, String token) {
        return rest.exchange("/api/v1/stock/" + sku + "/adjustments", HttpMethod.POST,
                withToken(token, req), StockResponse.class);
    }

    private ResponseEntity<String> adjustRaw(String sku, AdjustStockRequest req, String token) {
        return rest.exchange("/api/v1/stock/" + sku + "/adjustments", HttpMethod.POST,
                withToken(token, req), String.class);
    }

    // --- yetkilendirme ---

    @Test
    @DisplayName("token yoksa 401")
    void rejectsAnonymous() {
        assertThat(adjustRaw(stockedSku(10),
                new AdjustStockRequest("a-1", -1, AdjustmentReason.DAMAGED, null), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("müşteri rolü stok düzeltemez: 403")
    void rejectsCustomer() {
        String token = TestJwtSupport.tokenFor("musteri-1", "CUSTOMER");

        assertThat(adjustRaw(stockedSku(10),
                new AdjustStockRequest("a-2", -1, AdjustmentReason.DAMAGED, null), token)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- düzeltme ---

    @Test
    @DisplayName("eksi düzeltme stoğu azaltır")
    void appliesNegativeAdjustment() {
        String sku = stockedSku(100);

        ResponseEntity<StockResponse> response = adjust(sku,
                new AdjustStockRequest("a-" + sku, -7, AdjustmentReason.DAMAGED, "kırıldı"), admin());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().available()).isEqualTo(93);
    }

    @Test
    @DisplayName("AYNI düzeltme iki kez gönderilirse bir kez uygulanır")
    void isIdempotent() {
        String sku = stockedSku(100);
        AdjustStockRequest req =
                new AdjustStockRequest("a-" + sku, -7, AdjustmentReason.DAMAGED, null);
        adjust(sku, req, admin());

        ResponseEntity<StockResponse> retry = adjust(sku, req, admin());

        // JPA'nın save()'i kullanılsaydı bu 86 olurdu: var olan kimlik güncellenir,
        // "yeni kayıt" sanılır ve düzeltme ikinci kez uygulanırdı.
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().available()).isEqualTo(93);
    }

    @Test
    @DisplayName("stok kaydı olmayan SKU düzeltilemez: 404")
    void unknownSkuIsNotFound() {
        assertThat(adjustRaw("SKU-" + UUID.randomUUID(),
                new AdjustStockRequest("a-3", -1, AdjustmentReason.DAMAGED, null), admin())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("stoğu eksiye düşüren düzeltme reddedilir: 400")
    void rejectsNegativeResult() {
        String sku = stockedSku(3);

        assertThat(adjustRaw(sku,
                new AdjustStockRequest("a-" + sku, -5, AdjustmentReason.DAMAGED, null), admin())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("gerekçe zorunlu: reason yoksa 400")
    void reasonIsRequired() {
        String sku = stockedSku(10);

        assertThat(adjustRaw(sku,
                new AdjustStockRequest("a-" + sku, -1, null, null), admin())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("OTHER gerekçesi açıklamasız reddedilir: 400")
    void otherRequiresNote() {
        String sku = stockedSku(10);

        assertThat(adjustRaw(sku,
                new AdjustStockRequest("a-" + sku, -1, AdjustmentReason.OTHER, null), admin())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- denetim izi ---

    @Test
    @DisplayName("denetim izi okunabilir ve düzeltmeyi YAPANI token'dan alır")
    void auditTrailRecordsTheCaller() {
        String sku = stockedSku(50);
        adjust(sku, new AdjustStockRequest("a1-" + sku, -3, AdjustmentReason.SHRINKAGE, "sayım"), admin());
        adjust(sku, new AdjustStockRequest("a2-" + sku, 2, AdjustmentReason.COUNT_CORRECTION, null), admin());

        ResponseEntity<List<StockAdjustmentResponse>> history = rest.exchange(
                "/api/v1/stock/" + sku + "/adjustments", HttpMethod.GET,
                withToken(admin(), null), new ParameterizedTypeReference<>() {
                });

        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).hasSize(2);
        // En yeniden eskiye.
        assertThat(history.getBody().get(0).delta()).isEqualTo(2);
        assertThat(history.getBody().get(1).delta()).isEqualTo(-3);
        assertThat(history.getBody().get(1).reason()).isEqualTo("SHRINKAGE");
        assertThat(history.getBody().get(1).note()).isEqualTo("sayım");
        // ASIL İDDİA: kimlik istekten değil TOKEN'dan geldi. Gövdede "kim" alanı
        // olsaydı, isteyen başkasının adına düzeltme yazabilirdi.
        assertThat(history.getBody()).allSatisfy(
                a -> assertThat(a.adjustedBy()).isEqualTo(ADMIN_SUBJECT));
    }
}
