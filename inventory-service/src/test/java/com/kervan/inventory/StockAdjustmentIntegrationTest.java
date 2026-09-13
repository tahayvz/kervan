package com.kervan.inventory;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.security.TestJwtSupport;
import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
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

import java.time.Instant;
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

    @Autowired
    private StockAdjustmentRepository adjustmentRepository;

    /**
     * Depoya doğrudan yazarken transaction gerekiyor: {@code @Modifying} sorgular
     * transaction dışında çalışmaz.
     *
     * <p>Test metoduna {@code @Transactional} koymak YANLIŞ olurdu — o durumda yazılan
     * satırlar test sonunda geri alınır ve ayrı transaction'larda çalışan HTTP
     * istekleri onları hiç göremezdi.
     */
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

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

    private AdjustmentPageResponse history(String sku, String query) {
        ResponseEntity<AdjustmentPageResponse> response = rest.exchange(
                "/api/v1/stock/" + sku + "/adjustments" + query, HttpMethod.GET,
                withToken(admin(), null), AdjustmentPageResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("denetim izi okunabilir ve düzeltmeyi YAPANI token'dan alır")
    void auditTrailRecordsTheCaller() {
        String sku = stockedSku(50);
        adjust(sku, new AdjustStockRequest("a1-" + sku, -3, AdjustmentReason.SHRINKAGE, "sayım"), admin());
        adjust(sku, new AdjustStockRequest("a2-" + sku, 2, AdjustmentReason.COUNT_CORRECTION, null), admin());

        AdjustmentPageResponse page = history(sku, "");

        assertThat(page.items()).hasSize(2);
        // En yeniden eskiye.
        assertThat(page.items().get(0).delta()).isEqualTo(2);
        assertThat(page.items().get(1).delta()).isEqualTo(-3);
        assertThat(page.items().get(1).reason()).isEqualTo("SHRINKAGE");
        assertThat(page.items().get(1).note()).isEqualTo("sayım");
        // Devamı yok: son sayfada işaret verilmemeli.
        assertThat(page.nextCursor()).isNull();
        // ASIL İDDİA: kimlik istekten değil TOKEN'dan geldi. Gövdede "kim" alanı
        // olsaydı, isteyen başkasının adına düzeltme yazabilirdi.
        assertThat(page.items()).allSatisfy(
                a -> assertThat(a.adjustedBy()).isEqualTo(ADMIN_SUBJECT));
    }

    @Test
    @DisplayName("sayfa çevrilerek TÜM kayıtlar okunur, hiçbiri tekrarlanmaz")
    void pagesThroughEveryRecord() {
        String sku = stockedSku(100);
        for (int i = 0; i < 5; i++) {
            adjust(sku, new AdjustStockRequest("a" + i + "-" + sku, -1,
                    AdjustmentReason.DAMAGED, null), admin());
        }

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            AdjustmentPageResponse page = history(sku,
                    cursor == null ? "?size=2" : "?size=2&cursor=" + cursor);
            page.items().forEach(a -> seen.add(a.adjustmentId()));
            cursor = page.nextCursor();
        } while (cursor != null && ++guard < 10);

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("AYNI ANDA yazılmış kayıtlarda sayfalama hiçbirini ATLAMAZ")
    void samePreciseInstantIsNotSkipped() {
        // Bu testin varlık sebebi: sıralama yalnızca zamana bakıp kimliği hesaba
        // katmasaydı, aynı anı paylaşan iki kaydın arasına düşen sayfa sınırı
        // birini GÖRÜNMEZ yapardı. Bir denetim izinde "bazen bir kayıt atlanıyor"
        // kabul edilemez.
        //
        // API üzerinden aynı anı zorlamak mümkün değil (saat her çağrıda ilerler),
        // o yüzden riskli katman -- depo ve SQL -- doğrudan sınanıyor.
        //
        // NE KADARINI KANITLADIĞI: bu test WHERE'deki kimlik sınırını koruyor.
        // Mutasyonla doğrulandı: sınır kaldırıldığında 3 kayıttan 2'si görüldü.
        //
        // NE KADARINI KANITLAMADIĞI: ORDER BY'daki kimlik. O da gerekli -- sıralama
        // yalnızca zamana bakarsa aynı anı paylaşan satırların sırası veritabanının
        // keyfine kalır ve sayfa sınırı yine yanlış yere düşebilir. Ama denendi:
        // ORDER BY'dan kimlik çıkarıldığında bu test YEŞİL kaldı, çünkü PostgreSQL
        // o küçük tabloda tesadüfen uygun sırada döndürdü. Yani o satır bilerek
        // duruyor ama testle korunmuyor; ona dokunan dikkatli olmalı.
        String sku = "SKU-" + UUID.randomUUID();
        Instant sameMoment = Instant.parse("2026-03-10T12:00:00Z");
        transactions.executeWithoutResult(status -> {
            for (String id : List.of("id-a", "id-b", "id-c")) {
                adjustmentRepository.saveIfNew(new StockAdjustment(
                        id + "-" + sku, sku, -1, AdjustmentReason.DAMAGED, null,
                        ADMIN_SUBJECT, sameMoment));
            }
        });

        AdjustmentPageResponse first = history(sku, "?size=2");
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        AdjustmentPageResponse second = history(sku, "?size=2&cursor=" + first.nextCursor());

        List<String> all = new java.util.ArrayList<>();
        first.items().forEach(a -> all.add(a.adjustmentId()));
        second.items().forEach(a -> all.add(a.adjustmentId()));
        assertThat(all).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("bozuk işaret 400 döner, sessizce ilk sayfaya dönmez")
    void brokenCursorIsRejected() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/stock/" + stockedSku(10) + "/adjustments?cursor=bozuk!!",
                HttpMethod.GET, withToken(admin(), null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
