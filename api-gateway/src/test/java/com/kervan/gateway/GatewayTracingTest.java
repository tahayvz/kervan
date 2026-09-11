package com.kervan.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ağ geçidinin izleme (trace) bağlamını aşağı akışa ilettiğini doğrular.
 *
 * <h2>Neden ayrı bir sınıf?</h2>
 * {@link AutoConfigureObservability} yüzünden. Spring Boot testlerde izlemeyi
 * <b>varsayılan olarak kapatır</b>: bir test sınıfı bu anotasyonu taşımıyorsa
 * {@code management.tracing.enabled=false} eklenir. O hâlde {@code Propagator}
 * bean'i "hiçbir alan taşımayan" bir sürüm olur ve hiçbir başlık yazılmaz.
 *
 * <p>Bu, sessiz bir tuzaktır: izleme testi yazarsın, yeşil olur, hiçbir şey
 * ölçmez. Bu yüzden izlemeyi açan testler ayrı tutuldu — böylece anotasyonun
 * neden orada olduğu görünür ve yanlışlıkla silinmesi testi kırar.
 *
 * <p>Diğer ağ geçidi testleri ({@code GatewayTest}) izlemeyi açmaz: yönlendirme
 * ve kimlik doğrulama davranışı izlemeden bağımsızdır ve her bağlamda span
 * üretmek testleri yavaşlatırdı.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(TestJwtSupport.class)
@DisplayName("Ağ geçidi: izleme bağlamının iletilmesi")
class GatewayTracingTest {

    private static final MockWebServer CATALOG = new MockWebServer();

    @BeforeAll
    static void startDownstream() throws IOException {
        CATALOG.start();
    }

    @AfterAll
    static void stopDownstream() throws IOException {
        CATALOG.shutdown();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Yer tutucular üzerinden: gerekçesi GatewayTest'te yazılı (Spring liste
        // özelliklerini kaynaklar arasında birleştirmez).
        registry.add("KERVAN_CATALOG_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("KERVAN_ORDER_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("KERVAN_SEARCH_URL", () -> "http://localhost:" + CATALOG.getPort());
        // Span'leri gönderecek bir toplayıcı yok. Dışa aktarımı kapatmak, her
        // testin sonunda başarısız bağlantı denemesi ve hata log'u üretmesini
        // önler. Bağlamın YAYILMASI dışa aktarımdan bağımsızdır.
        registry.add("management.otlp.tracing.export.enabled", () -> "false");
    }

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("aşağı akış isteği traceparent başlığı taşır")
    void propagatesTraceContextDownstream() throws InterruptedException {
        CATALOG.enqueue(new MockResponse().setBody("[]").setHeader("Content-Type", "application/json"));

        client.get().uri("/api/v1/products/42").exchange().expectStatus().isOk();

        RecordedRequest received = CATALOG.takeRequest(2, TimeUnit.SECONDS);
        assertThat(received).isNotNull();

        // Ağ geçidi zincirin ilk halkasıdır: izi o başlatır ve aşağı akışa iletir.
        // Başlık gitmezse katalog servisi kendi ayrı izini başlatır ve bir isteğin
        // ağ geçidinde mi yoksa arka serviste mi yavaşladığı görünmez olur.
        String traceParent = received.getHeader("traceparent");
        assertThat(traceParent)
                .withFailMessage("traceparent başlığı aşağı akışa iletilmedi")
                .isNotNull();

        // Biçim W3C sözleşmesidir: sürüm-trace-span-bayrak. Bozuk biçimli bir
        // başlığı karşı taraf hata vermeden atar, zincir sessizce kopar.
        assertThat(traceParent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }
}
