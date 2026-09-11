package com.kervan.gateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hız sınırlamasının gerçekten uygulandığını doğrular.
 *
 * <p>Sınır düşük tutuluyor (saniyede 2, kova 2): testin sınırı aşması için yüzlerce
 * istek atması gerekmesin. Ölçülen şey sayının kendisi değil, <b>sınırın var olduğu
 * ve aşıldığında 429 döndüğü</b>.
 *
 * <p>Sayaçlar gerçek Redis'te tutuluyor. Bellekte tutulsaydı test geçerdi ama üretimde
 * her ağ geçidi kopyası kendi sınırını uygular, sınır kopya sayısıyla çarpılırdı —
 * yani testin doğruladığı şey üretimdeki davranış olmazdı.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Hız sınırlama")
class RateLimitTest {

    private static final MockWebServer CATALOG = new MockWebServer();

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @BeforeAll
    static void start() throws IOException {
        REDIS.start();
        CATALOG.start();
        CATALOG.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
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
        registry.add("spring.data.redis.url",
                () -> "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
        registry.add("KERVAN_CATALOG_URL", () -> "http://localhost:" + CATALOG.getPort());
        registry.add("KERVAN_RATE_LIMIT_PER_SECOND", () -> "2");
        registry.add("KERVAN_RATE_LIMIT_BURST", () -> "2");
    }

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("sınırı aşan istek 429 alır")
    void tooManyRequestsAreRejected() {
        // Kova 2 jetonla dolu; arka arkaya gelen istekler onu tüketir ve dolum
        // (saniyede 2) yetişemez.
        int rejected = 0;
        for (int i = 0; i < 12; i++) {
            int status = client.get().uri("/api/v1/products")
                    .exchange().returnResult(String.class).getStatus().value();
            if (status == 429) {
                rejected++;
            }
        }

        assertThat(rejected)
                .withFailMessage("Hiçbir istek sınıra takılmadı: sınırlama uygulanmıyor")
                .isPositive();
    }

    @Test
    @DisplayName("jetonlar dolunca istek yeniden geçer")
    void allowsRequestsAgainAfterRefill() throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            client.get().uri("/api/v1/products").exchange();
        }

        // Kova dolsun. Sınır kalıcı bir yasak değil, bir hız sınırı.
        Thread.sleep(Duration.ofSeconds(2).toMillis());

        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
    }
}
