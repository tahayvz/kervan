package com.kervan.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ağ geçidinin testleri.
 *
 * <p>Downstream servisler yerine iki sahte HTTP sunucusu çalışır. Böylece
 * <em>ağ geçidinin</em> davranışı ölçülür: isteği doğru servise mi gönderiyor,
 * token'ı iletiyor mu, kimliksiz isteği kenarda kesiyor mu. Gerçek catalog ve
 * order servislerini ayağa kaldırmak bu soruların hiçbirini daha iyi cevaplamaz,
 * yalnızca testi yavaşlatır ve kırılgan yapar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
class GatewayTest {

    private static final MockWebServer CATALOG = new MockWebServer();
    private static final MockWebServer ORDERS = new MockWebServer();
    private static final MockWebServer SEARCH = new MockWebServer();

    @BeforeAll
    static void startDownstream() throws IOException {
        CATALOG.start();
        ORDERS.start();
        SEARCH.start();
    }

    @AfterAll
    static void stopDownstream() throws IOException {
        CATALOG.shutdown();
        ORDERS.shutdown();
        SEARCH.shutdown();
    }

    /**
     * Downstream adresleri yapılandırmadaki YER TUTUCULAR üzerinden değiştiriliyor.
     *
     * <p>Önce {@code spring.cloud.gateway.routes[0].uri} doğrudan yazılmıştı ve
     * uygulama hiç açılmadı: <em>"Binding validation errors on spring.cloud.gateway
     * — must not be empty"</em>. Sebebi şu: Spring liste özelliklerini kaynaklar
     * arasında BİRLEŞTİRMEZ. Testin özellik kaynağı listenin bir elemanına dokununca
     * tüm listeyi devralır, geri kalan alanlar (id, predicates) boş kalır.
     *
     * <p>Yer tutucuyu değiştirmek hem bu tuzağa düşmez hem de gerçek yapılandırmayı
     * olduğu gibi çalıştırır: test edilen rota, üretimde çalışacak rotanın aynısıdır.
     */
    @DynamicPropertySource
    static void downstreamAddresses(DynamicPropertyRegistry registry) {
        registry.add("KERVAN_CATALOG_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("KERVAN_ORDER_URL", () -> "http://localhost:" + ORDERS.getPort());
        registry.add("KERVAN_SEARCH_URL", () -> "http://localhost:" + SEARCH.getPort());
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void drainPendingRequests() throws InterruptedException {
        // Onceki testten artan kayit kalmasin: yoksa "hangi istek nereye gitti"
        // iddialari bir onceki testin istegini okur ve yaniltir.
        while (CATALOG.takeRequest(1, TimeUnit.MILLISECONDS) != null) { /* bosalt */ }
        while (ORDERS.takeRequest(1, TimeUnit.MILLISECONDS) != null) { /* bosalt */ }
        while (SEARCH.takeRequest(1, TimeUnit.MILLISECONDS) != null) { /* bosalt */ }
    }

    @Nested
    @DisplayName("Yönlendirme")
    class Routing {

        @Test
        @DisplayName("Ürün isteği katalog servisine gider")
        void productsGoToCatalog() throws InterruptedException {
            CATALOG.enqueue(new MockResponse().setBody("[]").setHeader("Content-Type", "application/json"));

            client.get().uri("/api/v1/products/42").exchange().expectStatus().isOk();

            RecordedRequest received = CATALOG.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getPath()).isEqualTo("/api/v1/products/42");
            assertThat(ORDERS.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("Arama isteği arama servisine gider")
        void searchGoesToSearchService() throws InterruptedException {
            SEARCH.enqueue(new MockResponse().setBody("{}").setHeader("Content-Type", "application/json"));

            // Kimlik doğrulaması yok: arama katalog listeleme gibi açık.
            client.get().uri("/api/v1/search/products?q=kulaklik")
                    .exchange().expectStatus().isOk();

            RecordedRequest received = SEARCH.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getPath()).startsWith("/api/v1/search/products");
            // Sayaç değil kuyruk kontrol ediliyor: getRequestCount() sınıfın ömrü
            // boyunca birikir ve önceki testlerin isteklerini de sayar.
            assertThat(CATALOG.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        }

        @Test
        @DisplayName("Sipariş isteği sipariş servisine gider")
        void ordersGoToOrderService() throws InterruptedException {
            ORDERS.enqueue(new MockResponse().setBody("{}").setHeader("Content-Type", "application/json"));

            client.get().uri("/api/v1/orders/abc")
                    .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("musteri-1", "CUSTOMER"))
                    .exchange().expectStatus().isOk();

            RecordedRequest received = ORDERS.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getPath()).isEqualTo("/api/v1/orders/abc");
        }

        @Test
        @DisplayName("Tanımlı olmayan yol hiçbir servise gitmez")
        void unknownPathIsNotRouted() {
            client.get().uri("/api/v1/invoices")
                    .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("musteri-1", "CUSTOMER"))
                    .exchange().expectStatus().isNotFound();
        }

        @Test
        @DisplayName("Token downstream servise AYNEN iletilir")
        void tokenIsForwardedDownstream() throws InterruptedException {
            // Ag gecidi tek savunma hatti degil: servisler de kendi dogrulamasini
            // yapar. Token asagi tasinmazsa servis kullaniciyi taniyamaz ve
            // kayit bazli sahiplik denetimi calismaz.
            String token = TestJwtSupport.tokenFor("musteri-7", "CUSTOMER");
            ORDERS.enqueue(new MockResponse().setBody("{}"));

            client.get().uri("/api/v1/orders/xyz")
                    .header("Authorization", "Bearer " + token)
                    .exchange().expectStatus().isOk();

            RecordedRequest received = ORDERS.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getHeader("Authorization")).isEqualTo("Bearer " + token);
        }
    }

    @Nested
    @DisplayName("Kimlik doğrulama")
    class Authentication {

        @Test
        @DisplayName("Tokensız sipariş isteği 401 döner ve servise HİÇ ULAŞMAZ")
        void anonymousOrderRequestIsRejectedAtTheEdge() {
            client.get().uri("/api/v1/orders/abc").exchange().expectStatus().isUnauthorized();

            // Asil kazanc bu: kimliksiz trafik kenarda kesilir, arka servis hic
            // yorulmaz. Yalnizca 401'i dogrulamak bunu kanitlamazdi.
            assertThat(ORDERS.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("Yabancı anahtarla imzalanmış token reddedilir")
        void foreignSignatureIsRejected() {
            client.get().uri("/api/v1/orders/abc")
                    .header("Authorization", "Bearer " + TestJwtSupport.tokenSignedByStranger("x", "CUSTOMER"))
                    .exchange().expectStatus().isUnauthorized();

            assertThat(ORDERS.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("Süresi geçmiş token reddedilir")
        void expiredTokenIsRejected() {
            client.get().uri("/api/v1/orders/abc")
                    .header("Authorization", "Bearer " + TestJwtSupport.expiredTokenFor("x", "CUSTOMER"))
                    .exchange().expectStatus().isUnauthorized();

            assertThat(ORDERS.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("Ürün okuması tokensız çalışır")
        void productReadsStayPublic() throws InterruptedException {
            // Urun listesi bir vitrindir. Ag gecidi servisin bugunku davranisini
            // degistirmemeli: gormek icin hesap gerekmiyordu, gerekmemeli.
            CATALOG.enqueue(new MockResponse().setBody("[]"));

            client.get().uri("/api/v1/products").exchange().expectStatus().isOk();

            assertThat(CATALOG.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        }

        @Test
        @DisplayName("Sağlık ucu tokensız açıktır")
        void healthIsPublic() {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }
}
