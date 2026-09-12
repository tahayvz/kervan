package com.kervan.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Devre kesicinin gerçekten devreye girdiğini gösterir.
 *
 * <p>Bu testlerin varlık sebebi şu: dayanıklılık ayarları yazılır, kimse
 * kırılmadığı için doğru sanılır ve gerçek arıza anında çalışmadığı görülür.
 * Ayar dosyasına bakarak "devre kesici var" demek, onun <b>açıldığını</b>
 * kanıtlamaz.
 *
 * <p>Üç şey ayrı ayrı doğrulanıyor:
 * <ol>
 *   <li>Yavaş servis, zaman aşımıyla kesilip geri düşüşe gidiyor mu?</li>
 *   <li>Kesici açıldıktan sonra istekler arka servise <b>hiç ulaşmıyor</b> mu?
 *       Asıl koruma budur.</li>
 *   <li>Bir rotanın kesicisi diğer rotayı etkiliyor mu?</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
@DisplayName("Devre kesici")
class GatewayCircuitBreakerTest {

    private static final MockWebServer CATALOG = new MockWebServer();
    private static final MockWebServer SEARCH = new MockWebServer();
    private static final MockWebServer ORDERS = new MockWebServer();

    @BeforeAll
    static void start() throws IOException {
        CATALOG.start();
        SEARCH.start();
        ORDERS.start();
    }

    @AfterAll
    static void stop() throws IOException {
        CATALOG.shutdown();
        SEARCH.shutdown();
        ORDERS.shutdown();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("KERVAN_CATALOG_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("KERVAN_SEARCH_URL", () -> "http://localhost:" + SEARCH.getPort());
        registry.add("KERVAN_ORDER_URL", () -> "http://localhost:" + ORDERS.getPort());
        registry.add("management.server.port", () -> "0");
        // Hiz siniri bu testin konusu degil; sinira takilan istek arka servise
        // hic gitmez ve sayimi bozardi.
        registry.add("KERVAN_RATE_LIMIT_PER_SECOND", () -> "1000");
        registry.add("KERVAN_RATE_LIMIT_BURST", () -> "2000");
    }

    @Autowired
    private WebTestClient client;

    /**
     * Devre kesicinin durumu PAYLASILAN bir durumdur: bir test onu actiginda
     * sonraki test daha ilk istekte geri dusus alir ve yanlis sey olculur.
     * Her testten once sifirlaniyor.
     */
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void isolateTests() throws InterruptedException {
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);

        // Yonlendirici de sifirlanir: bir testin setDispatcher'i kaliyor ve
        // sonraki testin enqueue() cagrisi ClassCastException veriyordu.
        // Paylasilan durum, testleri birbirine bagimli kilar.
        for (MockWebServer server : new MockWebServer[]{CATALOG, SEARCH, ORDERS}) {
            server.setDispatcher(new QueueDispatcher());
            while (server.takeRequest(1, TimeUnit.MILLISECONDS) != null) { /* bosalt */ }
        }
    }

    @Test
    @DisplayName("cevap vermeyen servis zaman aşımıyla kesilir ve 503 döner")
    void slowDownstreamIsCutOffByTheTimeLimiter() {
        // Hic cevap vermeyen bir servis. ZAMAN ASIMI OLMASAYDI istek burada
        // askida kalirdi: istemci bekler, ag gecidinde kaynak tutulur ve devre
        // kesici hic tetiklenmez -- cunku askida kalmak "basarisizlik" degildir.
        SEARCH.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        long startedAt = System.nanoTime();
        client.get().uri("/api/v1/search/products?q=kulaklik")
                .exchange()
                .expectStatus().isEqualTo(503)
                // 500 degil 503: "hata yaptim" ile "su an veremiyorum" ayri seyler.
                // Retry-After istemciye NE ZAMAN tekrar deneyecegini soyler.
                .expectHeader().valueEquals("Retry-After", "10")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("search");

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed)
                .withFailMessage("zaman aşımı devrede değil: %s sürdü", elapsed)
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("kesici açıldıktan sonra istekler arka servise hiç ulaşmaz")
    void openBreakerStopsTrafficReachingTheDownstream() throws InterruptedException {
        // Arka servis surekli 503 donuyor: "ben ayakta degilim" diyor.
        CATALOG.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                return new MockResponse().setResponseCode(503);
            }
        });

        // Kesicinin karar verebilmesi icin en az `minimumNumberOfCalls` cagri
        // gerekir; oncesinde tek bir hata oranı %100 gorunur ve kesici erken
        // acilirdi.
        for (int i = 0; i < 15; i++) {
            client.get().uri("/api/v1/products/" + i).exchange().expectStatus().isEqualTo(503);
        }

        int reachedBeforeOpening = countRequests(CATALOG);

        // Kesici artik acik olmali. Bundan sonraki istekler arka servise
        // GITMEMELI -- asil koruma budur: coken servise yuk binmeye devam etmez
        // ve cevap aninda doner.
        for (int i = 0; i < 5; i++) {
            client.get().uri("/api/v1/products/x" + i).exchange().expectStatus().isEqualTo(503);
        }

        assertThat(countRequests(CATALOG))
                .withFailMessage("kesici açık değil: arka servis istek almaya devam etti")
                .isZero();
        assertThat(reachedBeforeOpening)
                .withFailMessage("arka servis hiç istek almadı; test yanlış şeyi ölçüyor")
                .isPositive();
    }

    @Test
    @DisplayName("bir rotanın kesicisi diğer rotayı etkilemez")
    void breakersAreIsolatedPerRoute() throws InterruptedException {
        // Arama tarafi tamamen cokuk.
        SEARCH.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                return new MockResponse().setResponseCode(503);
            }
        });
        for (int i = 0; i < 15; i++) {
            client.get().uri("/api/v1/search/products?q=" + i).exchange().expectStatus().isEqualTo(503);
        }

        // Siparis tarafi saglikli ve calismaya DEVAM etmeli. Tek ortak kesici
        // olsaydi burasi da kapanirdi: birbirini tanimayan iki servis birbirinin
        // arizasini paylasirdi.
        ORDERS.enqueue(new MockResponse().setBody("{}").setHeader("Content-Type", "application/json"));

        client.get().uri("/api/v1/orders/abc")
                .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("c-1", "CUSTOMER"))
                .exchange().expectStatus().isOk();

        assertThat(ORDERS.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }

    /** Kuyrukta biriken istek sayısını sayar ve kuyruğu boşaltır. */
    private static int countRequests(MockWebServer server) throws InterruptedException {
        int count = 0;
        while (server.takeRequest(200, TimeUnit.MILLISECONDS) != null) {
            count++;
        }
        return count;
    }
}
