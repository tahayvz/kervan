package com.kervan.gateway;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Redis erişilemezken ağ geçidi ne yapıyor?
 *
 * <p>İki seçenek var ve ikisi de savunulabilir görünüyor: sınırı uygulayamadığın için
 * isteği <b>reddetmek</b>, ya da geçirmek.
 *
 * <p>Burada geçirmek seçildi. Hız sınırı bir korumadır, bir ön koşul değil. Reddetmek
 * şu anlama gelirdi: <em>sayaç deposundaki bir arıza, bütün API'yi kapatır</em> — yani
 * koruma, koruduğu şeyi yok eder. Sınırsız kalmanın riski gerçek ama geçici; kapının
 * tamamen kapanması kesin bir kesintidir.
 *
 * <p>Aynı sebeple Redis, ağ geçidinin sağlık ucuna dahil edilmedi: DOWN görünseydi
 * Kubernetes trafiği keserdi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Redis erişilemezken")
class RateLimitFailureTest {

    private static final MockWebServer CATALOG = new MockWebServer();

    /** Hiçbir şeyin dinlemediği bir port: bağlantı reddedilir. */
    private static int deadPort;

    @BeforeAll
    static void start() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        CATALOG.start();
        CATALOG.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("[]")
                        .setHeader("Content-Type", "application/json");
            }
        });
    }

    @AfterAll
    static void stop() throws IOException {
        CATALOG.shutdown();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://localhost:" + deadPort);
        registry.add("KERVAN_CATALOG_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient client;

    /**
     * Actuator ana portta değil, ayrı bir yönetim portunda ({@code management.server.port}).
     * Test onu 0 yaparak rastgele bir porta bağlar; {@code @LocalManagementPort} o portu verir.
     */
    @LocalManagementPort
    private int managementPort;

    private WebTestClient management() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build();
    }

    @Test
    @DisplayName("istekler geçmeye devam eder")
    void requestsStillPass() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("sağlık ucu yine de 200 döner")
    void healthStaysUp() {
        // Redis sağlığa dahil edilseydi burası 503 dönerdi ve Kubernetes bu kopyayı
        // trafikten çıkarırdı — sayaç deposu yüzünden ön kapı kapanırdı.
        management().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
