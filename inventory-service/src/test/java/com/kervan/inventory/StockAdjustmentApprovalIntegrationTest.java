package com.kervan.inventory;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.security.TestJwtSupport;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.AdjustmentPageResponse;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import com.kervan.inventory.web.dto.StockResponse;
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
 * Büyük düzeltmelerde ikinci onay (ADR-0022), gerçek veritabanına karşı.
 *
 * <p>Bu testlerin varlık sebebi: bu bir <b>kontrol</b> ve kontroller sessizce
 * gevşeyebilen türden koddur. Eşik yanlışlıkla yükseltilse, "isteyen kendi isteğini
 * onaylayamaz" kuralı kalksa ya da bekleyen kayıt stoğu değiştirse — uygulama
 * çalışmaya devam eder, hiçbir hata çıkmaz ve koruma ortadan kalkar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@DisplayName("Büyük düzeltmede ikinci onay")
class StockAdjustmentApprovalIntegrationTest extends AbstractIntegrationTest {

    private static final String AYSE = "ayse-sub";
    private static final String MEHMET = "mehmet-sub";
    /** Varsayılan eşik 100; bunun üstü onay bekler. */
    private static final int BIG = 500;
    private static final int SMALL = 5;

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<Object> as(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.tokenFor(subject, "ADMIN"));
        return new HttpEntity<>(body, headers);
    }

    private String stockedSku(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        assertThat(rest.exchange("/api/v1/stock/" + sku + "/receipts", HttpMethod.POST,
                as(AYSE, new ReceiveStockRequest("r-" + sku, quantity)), StockResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return sku;
    }

    private ResponseEntity<StockResponse> adjust(String sku, String id, int delta, String who) {
        return rest.exchange("/api/v1/stock/" + sku + "/adjustments", HttpMethod.POST,
                as(who, new AdjustStockRequest(id, delta, AdjustmentReason.SHRINKAGE, null)),
                StockResponse.class);
    }

    private ResponseEntity<String> approve(String id, String who) {
        return rest.exchange("/api/v1/stock/adjustments/" + id + "/approve", HttpMethod.POST,
                as(who, null), String.class);
    }

    private ResponseEntity<String> reject(String id, String who) {
        return rest.exchange("/api/v1/stock/adjustments/" + id + "/reject", HttpMethod.POST,
                as(who, null), String.class);
    }

    private int availableOf(String sku) {
        return rest.exchange("/api/v1/stock/" + sku, HttpMethod.GET,
                as(AYSE, null), StockResponse.class).getBody().available();
    }

    private AdjustmentPageResponse history(String sku) {
        return rest.exchange("/api/v1/stock/" + sku + "/adjustments", HttpMethod.GET,
                as(AYSE, null), AdjustmentPageResponse.class).getBody();
    }

    // --- eşik ---

    @Test
    @DisplayName("küçük düzeltme ANINDA uygulanır: 200")
    void smallAdjustmentAppliesImmediately() {
        String sku = stockedSku(1000);

        ResponseEntity<StockResponse> response = adjust(sku, "a-" + sku, -SMALL, AYSE);

        // Eşiğin altı onay beklemez; yoksa "bir kutu ezilmiş, 3 adet düş" demek için
        // ikinci kişi aramak gerekir ve kimse sistemi kullanmaz.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(availableOf(sku)).isEqualTo(995);
    }

    @Test
    @DisplayName("büyük düzeltme 202 döner ve STOĞA DOKUNMAZ")
    void bigAdjustmentIsAcceptedButNotApplied() {
        String sku = stockedSku(1000);

        ResponseEntity<StockResponse> response = adjust(sku, "a-" + sku, -BIG, AYSE);

        // 202 = "isteğini aldım, henüz uygulamadım". 200 dönseydi istemci stoğu
        // değişmiş sanardı.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(availableOf(sku)).isEqualTo(1000);
        assertThat(history(sku).items().get(0).status()).isEqualTo("PENDING");
    }

    // --- asıl koruma ---

    @Test
    @DisplayName("İSTEYEN KENDİ İSTEĞİNİ ONAYLAYAMAZ: 403")
    void requesterCannotApproveOwnRequest() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -BIG, AYSE);

        ResponseEntity<String> response = approve(id, AYSE);

        // İkinci onayın TAMAMI bu. Eşik yalnızca hangi düzeltmelerin onaya
        // düşeceğini söyler; korumayı sağlayan şey bu satır.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(availableOf(sku)).isEqualTo(1000);
    }

    @Test
    @DisplayName("BAŞKASI onaylayınca stok o anda değişir")
    void anotherAdminApprovesAndStockMoves() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -BIG, AYSE);

        assertThat(approve(id, MEHMET).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(availableOf(sku)).isEqualTo(500);
        var record = history(sku).items().get(0);
        assertThat(record.status()).isEqualTo("APPROVED");
        // Denetim izi İKİ kişiyi de tutar: isteyen ve onaylayan.
        assertThat(record.adjustedBy()).isEqualTo(AYSE);
        assertThat(record.decidedBy()).isEqualTo(MEHMET);
        assertThat(record.decidedAt()).isNotNull();
    }

    @Test
    @DisplayName("aynı düzeltme İKİNCİ KEZ onaylanamaz: 409")
    void cannotApproveTwice() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -BIG, AYSE);
        approve(id, MEHMET);

        ResponseEntity<String> second = approve(id, "zeynep-sub");

        // Aksi hâlde stok İKİ KEZ düşerdi.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(availableOf(sku)).isEqualTo(500);
    }

    // --- ret ---

    @Test
    @DisplayName("reddedilen düzeltmede stok HİÇ değişmez")
    void rejectedAdjustmentNeverMovesStock() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -BIG, AYSE);

        assertThat(reject(id, MEHMET).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(availableOf(sku)).isEqualTo(1000);
        var record = history(sku).items().get(0);
        assertThat(record.status()).isEqualTo("REJECTED");
        assertThat(record.decidedBy()).isEqualTo(MEHMET);
    }

    @Test
    @DisplayName("reddedilen düzeltme sonradan onaylanamaz: 409")
    void rejectedCannotBeApprovedLater() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -BIG, AYSE);
        reject(id, MEHMET);

        assertThat(approve(id, "zeynep-sub").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(availableOf(sku)).isEqualTo(1000);
    }

    // --- sınır durumları ---

    @Test
    @DisplayName("anında uygulanmış küçük düzeltme onaya açılamaz: 409")
    void appliedAdjustmentCannotBeApproved() {
        String sku = stockedSku(1000);
        String id = "a-" + sku;
        adjust(sku, id, -SMALL, AYSE);

        assertThat(approve(id, MEHMET).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("var olmayan düzeltme onaylanamaz: 404")
    void unknownAdjustmentIsNotFound() {
        assertThat(approve("hic-boyle-bir-sey-yok", MEHMET).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("onay anında stok yetmiyorsa reddedilir: 400")
    void approvalRevalidatesAgainstCurrentStock() {
        String sku = stockedSku(1000);
        String bigId = "big-" + sku;
        adjust(sku, bigId, -BIG, AYSE);

        // Onay beklerken stok başka yollardan düştü.
        adjust(sku, "drain1-" + sku, -SMALL, AYSE);
        String drainId = "drain2-" + sku;
        adjust(sku, drainId, -600, AYSE);
        approve(drainId, MEHMET);

        // Elde 395 kaldı; bekleyen 500'lük düzeltme artık uygulanamaz.
        assertThat(availableOf(sku)).isEqualTo(395);
        assertThat(approve(bigId, MEHMET).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(availableOf(sku)).isEqualTo(395);
    }
}
