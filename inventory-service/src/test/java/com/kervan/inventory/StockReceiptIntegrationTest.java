package com.kervan.inventory;

import com.kervan.inventory.security.TestJwtSupport;
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
 * Mal kabulü ucunun GERÇEK veritabanına karşı davranışı (ADR-0019).
 *
 * <p>Birim testleri deponun sözleşmesini taklit eder; burada sınanan şey o sözleşmenin
 * <b>gerçekten</b> tuttuğudur. İdempotentlik iki {@code ON CONFLICT DO NOTHING}
 * ifadesine dayanıyor ve bunların doğru çalıştığı ancak PostgreSQL'e sorularak
 * görülebilir: sahte bir depo, yazdığımız SQL yanlış olsa bile "yazıldı" derdi.
 *
 * <p>Web sunucusu burada açılıyor ({@code RANDOM_PORT}); üst sınıf yalnızca
 * container'ları sağlar. Güvenlik de gerçek: istekler imzalı token'la gidiyor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@DisplayName("Mal kabulü ucu")
class StockReceiptIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static String admin() {
        return TestJwtSupport.tokenFor("admin-1", "ADMIN");
    }

    private <T> HttpEntity<T> withToken(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<StockResponse> receive(String sku, String receiptId, int qty, String token) {
        return rest.exchange("/api/v1/stock/" + sku + "/receipts", HttpMethod.POST,
                withToken(token, new ReceiveStockRequest(receiptId, qty)), StockResponse.class);
    }

    private String newSku() {
        return "SKU-" + UUID.randomUUID();
    }

    // --- yetkilendirme ---

    @Test
    @DisplayName("token yoksa 401")
    void rejectsAnonymous() {
        assertThat(receive(newSku(), "r-1", 10, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("müşteri rolü stok giremez: 403")
    void rejectsCustomer() {
        String token = TestJwtSupport.tokenFor("musteri-1", "CUSTOMER");

        assertThat(receive(newSku(), "r-2", 10, token).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("stok okuma da yöneticiye özel: müşteri 403 alır")
    void readIsAdminOnlyToo() {
        // Kalan stok ticari bilgidir; "son 2 adet" rakibe ipucu, kötü niyetliye hedef verir.
        ResponseEntity<String> response = rest.exchange("/api/v1/stock/" + newSku(), HttpMethod.GET,
                withToken(TestJwtSupport.tokenFor("musteri-1", "CUSTOMER"), null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- mal kabulü ---

    @Test
    @DisplayName("hiç görülmemiş SKU için stok kaydını açar")
    void createsStockForUnknownSku() {
        String sku = newSku();

        ResponseEntity<StockResponse> response = receive(sku, "r-" + sku, 40, admin());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().available()).isEqualTo(40);
        assertThat(response.getBody().reserved()).isZero();
        assertThat(response.getBody().total()).isEqualTo(40);
    }

    @Test
    @DisplayName("ikinci makbuz miktarı EKLER")
    void secondReceiptAccumulates() {
        String sku = newSku();
        receive(sku, "r1-" + sku, 40, admin());

        ResponseEntity<StockResponse> response = receive(sku, "r2-" + sku, 15, admin());

        assertThat(response.getBody().available()).isEqualTo(55);
    }

    @Test
    @DisplayName("AYNI makbuz iki kez gönderilirse miktar bir kez eklenir")
    void isIdempotent() {
        String sku = newSku();
        String receiptId = "r-" + sku;
        receive(sku, receiptId, 40, admin());

        // İstemci zaman aşımı aldı ve aynı isteği yeniden gönderdi.
        ResponseEntity<StockResponse> retry = receive(sku, receiptId, 40, admin());

        // Cevap yine başarılı ve stok DEĞİŞMEDİ. Bu iddia, ON CONFLICT DO NOTHING
        // yerine JPA'nın save()'i kullanılsaydı kırılırdı: save() var olan kimliği
        // günceller, "yeni kayıt" sanılır ve miktar ikinci kez eklenirdi.
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().available()).isEqualTo(40);
    }

    @Test
    @DisplayName("mal kabulü ayrılmış miktara dokunmaz")
    void doesNotTouchReserved() {
        String sku = newSku();
        receive(sku, "r-" + sku, 10, admin());

        ResponseEntity<StockResponse> response = receive(sku, "r2-" + sku, 5, admin());

        assertThat(response.getBody().reserved()).isZero();
        assertThat(response.getBody().available()).isEqualTo(15);
    }

    // --- doğrulama ---

    @Test
    @DisplayName("sıfır miktar reddedilir: 400")
    void rejectsZeroQuantity() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/stock/" + newSku() + "/receipts", HttpMethod.POST,
                withToken(admin(), new ReceiveStockRequest("r-0", 0)), String.class);

        // @Positive gerçekten işliyor mu? spring-boot-starter-validation bağımlılığı
        // olmasaydı anotasyon sessizce yok sayılır ve bu istek 200 dönerdi.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("makbuz kimliği boş olamaz: 400")
    void rejectsBlankReceiptId() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/stock/" + newSku() + "/receipts", HttpMethod.POST,
                withToken(admin(), new ReceiveStockRequest("  ", 5)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- okuma ---

    @Test
    @DisplayName("stok okunabilir")
    void readsStock() {
        String sku = newSku();
        receive(sku, "r-" + sku, 33, admin());

        ResponseEntity<StockResponse> response = rest.exchange("/api/v1/stock/" + sku,
                HttpMethod.GET, withToken(admin(), null), StockResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().available()).isEqualTo(33);
    }

    @Test
    @DisplayName("stok kaydı olmayan SKU için 404")
    void unknownSkuIsNotFound() {
        ResponseEntity<String> response = rest.exchange("/api/v1/stock/" + newSku(),
                HttpMethod.GET, withToken(admin(), null), String.class);

        // Sıfır stok dönmek yanlış olurdu: "hiç kaydı yok" ile "var ama bitti" farklı
        // şeylerdir ve ikincisi bir sipariş kararını değiştirir.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
