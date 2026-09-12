package com.kervan.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Ağ geçidinin metrikleri yönetim portunda yayınlandığını doğrular.
 *
 * <p>Ana kapıda yayınlanmadığı {@code GatewayTest} içinde ayrıca doğrulanıyor.
 * İkisi birlikte tek bir cümleyi sabitliyor: <b>metrikler var, ama dışarıya
 * açılan portta değil.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@DisplayName("Ağ geçidi metrikleri")
class GatewayMetricsTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> "0");
        registry.add("management.otlp.tracing.export.enabled", () -> "false");
    }

    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("Prometheus ucu yönetim portunda tokensız okunur")
    void exposesPrometheusOnTheManagementPort() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build()
                .get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                // RED panoları bu seriden beslenir.
                .expectBody(String.class).value(body ->
                        org.assertj.core.api.Assertions.assertThat(body)
                                .contains("jvm_memory_used_bytes"));
    }
}
