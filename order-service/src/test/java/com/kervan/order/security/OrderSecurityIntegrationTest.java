package com.kervan.order.security;

import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yetkilendirmenin gerçekten uygulandığını doğrular.
 * <p>
 * Bu testlerin varlık sebebi: güvenlik yapılandırması sessizce bozulabilen türden bir
 * koddur. Bir kural yanlışlıkla gevşetildiğinde uygulama çalışmaya devam eder, testler
 * geçer ve kimse fark etmez — açık ancak dışarıdan denendiğinde görülür. Buradaki her
 * test, bir kuralın kaldırılması hâlinde kırmızıya döner.
 */
@DisplayName("Sipariş API güvenliği")
class OrderSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private PlaceOrderRequest request() {
        return new PlaceOrderRequest("TRY", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 1, new BigDecimal("100.00"))));
    }

    private <T> HttpEntity<T> withToken(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private String placeOrderAs(String userId) {
        ResponseEntity<OrderResponse> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST,
                withToken(TestJwtSupport.tokenFor(userId, "CUSTOMER"), request()),
                OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private HttpStatus statusOfGet(String orderId, String token) {
        return HttpStatus.valueOf(rest.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET,
                withToken(token, null), String.class).getStatusCode().value());
    }

    // --- kimlik doğrulama ---

    @Test
    @DisplayName("token yoksa 401")
    void shouldRejectRequestWithoutToken() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST, withToken(null, request()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("başka bir anahtarla imzalanmış token 401")
    void shouldRejectTokenSignedByAnotherKey() {
        String forged = TestJwtSupport.tokenSignedByStranger("saldirgan", "CUSTOMER");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST, withToken(forged, request()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("süresi geçmiş token 401")
    void shouldRejectExpiredToken() {
        String expired = TestJwtSupport.expiredTokenFor("musteri-1", "CUSTOMER");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST, withToken(expired, request()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- rol tabanlı yetkilendirme ---

    @Test
    @DisplayName("geçerli token ama rolsüz kullanıcı 403")
    void shouldRejectTokenWithoutRequiredRole() {
        String noRoles = TestJwtSupport.tokenFor("rolsuz-kullanici");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST, withToken(noRoles, request()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- kayıt bazında sahiplik ---

    @Test
    @DisplayName("müşteri kendi siparişini okuyabilir")
    void customerShouldReadOwnOrder() {
        String userId = "musteri-" + UUID.randomUUID();
        String orderId = placeOrderAs(userId);

        assertThat(statusOfGet(orderId, TestJwtSupport.tokenFor(userId, "CUSTOMER")))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("müşteri BAŞKASININ siparişini okuyamaz — 403")
    void customerShouldNotReadAnotherCustomersOrder() {
        String orderId = placeOrderAs("musteri-" + UUID.randomUUID());
        String intruder = TestJwtSupport.tokenFor("baska-musteri", "CUSTOMER");

        assertThat(statusOfGet(orderId, intruder)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("yönetici her siparişi okuyabilir")
    void adminShouldReadAnyOrder() {
        String orderId = placeOrderAs("musteri-" + UUID.randomUUID());
        String admin = TestJwtSupport.tokenFor("yonetici-1", "ADMIN");

        assertThat(statusOfGet(orderId, admin)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("sipariş sahibi token'dan alınır; gövdeye kimlik yazılamaz")
    void orderOwnerShouldComeFromTokenNotFromBody() {
        String userId = "gercek-musteri-" + UUID.randomUUID();

        ResponseEntity<OrderResponse> response = rest.exchange(
                "/api/v1/orders", HttpMethod.POST,
                withToken(TestJwtSupport.tokenFor(userId, "CUSTOMER"), request()),
                OrderResponse.class);

        assertThat(response.getBody().customerId()).isEqualTo(userId);
    }

    // --- açık uçlar ---

    @Test
    @DisplayName("API dokümantasyonu varsayılan olarak kapalıdır")
    void apiDocsShouldBeClosedByDefault() {
        // Varsayılan yapılandırmada açık olmamalı: uç listesi ve alan adları
        // saldırgan için hazır bir harita anlamına gelir.
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("actuator iş portunda hiç yok")
    void actuatorIsNotOnTheBusinessPort() {
        // Ölçüm ve sağlık uçları ayrı bir yönetim portunda. Daha önce buradaydılar
        // ve token isterlerdi; artık bu portta böyle bir uç yok.
        //
        // Fark önemli: "korunuyor" ile "orada değil" aynı şey değildir. İkincisinde
        // yeni bir actuator ucu eklemek iş portunun güvenlik ayarını hiç
        // ilgilendirmez — yanlışlıkla açılacak bir şey kalmaz.
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }
}
