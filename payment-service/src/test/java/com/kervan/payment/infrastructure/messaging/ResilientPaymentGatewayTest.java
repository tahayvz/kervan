package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.PaymentDeclinedException;
import com.kervan.payment.domain.model.PaymentProviderUnavailableException;
import com.kervan.payment.domain.port.PaymentGateway;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dayanıklılık zincirinin gerçekten iddia edilen şeyi yaptığını gösterir.
 *
 * <p>Ayar dosyasına bakıp "devre kesici var" demek, onun <b>doğru şeyi saydığını</b>
 * kanıtlamaz. Buradaki dört soru para ile ilgili ve yanlış cevabın bedeli somut:
 * müşteriye iki kez para çekmek, ya da çalışan bir sağlayıcıyı kapatmak.
 */
@DisplayName("ResilientPaymentGateway")
class ResilientPaymentGatewayTest {

    private static final Money AMOUNT =
            new Money(new BigDecimal("100.00"), Currency.getInstance("TRY"));

    private PaymentGateway delegate;
    private CircuitBreaker circuitBreaker;
    private ResilientPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        delegate = mock(PaymentGateway.class);

        // Uretimdekiyle AYNI semantik, kucuk sayilarla: test hizli olsun ama
        // davranis degismesin.
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .ignoreExceptions(PaymentDeclinedException.class)
                .build());

        Retry retry = Retry.of("test", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(new RetryableProviderFailures())
                .build());

        Bulkhead bulkhead = Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(8)
                .maxWaitDuration(Duration.ZERO)
                .build());

        gateway = new ResilientPaymentGateway(delegate, circuitBreaker, retry, bulkhead);
    }

    private void charge(String orderId) {
        gateway.charge(orderId, "c-1", AMOUNT);
    }

    @Test
    @DisplayName("reddedilme devre kesiciyi AÇMAZ — sağlayıcı çalışıyor")
    void declinesDoNotOpenTheBreaker() {
        when(delegate.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentDeclinedException("limit asildi"));

        // Bir ret dalgasi: kampanya sonrasi limit asimlari ya da dolandiricilik
        // kontrolleri. Saglayici SAGLIKLI, her seferinde duzgun cevap veriyor.
        for (int i = 0; i < 10; i++) {
            String orderId = "order-" + i;
            assertThatThrownBy(() -> charge(orderId))
                    .isInstanceOf(PaymentDeclinedException.class);
        }

        // Kesici acilsaydi, hicbir sey bozuk degilken butun odemeler kesilirdi.
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("istek sağlayıcıya ulaşmadıysa tekrar denenir")
    void retriesWhenTheRequestNeverReachedTheProvider() {
        when(delegate.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentProviderUnavailableException("baglanti reddedildi", false))
                .thenReturn("SIM-1");

        charge("order-1");

        // Ilk deneme hic ulasmadi, ikincisi basarili. Tekrar denemek guvenliydi.
        verify(delegate, times(2)).charge(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("istek ulaşmış OLABİLİYORSA tekrar denenmez — çift çekim riski")
    void doesNotRetryWhenTheChargeMayHaveBeenProcessed() {
        when(delegate.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentProviderUnavailableException("okuma zaman asimi", true));

        assertThatThrownBy(() -> charge("order-1"))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        // TEK cagri. Korlemesine tekrar denemek musteriden ikinci kez para
        // cekmek olurdu; siparisin iptal olmasi bundan cok daha ucuzdur.
        verify(delegate, times(1)).charge(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("tekrar denemeler kesiciye TEK başarısızlık yazılır")
    void retriesCountAsOneFailureForTheBreaker() {
        when(delegate.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentProviderUnavailableException("baglanti reddedildi", false));

        assertThatThrownBy(() -> charge("order-1"))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        // Saglayiciya UC cagri gitti (1 + 2 tekrar)...
        verify(delegate, times(3)).charge(anyString(), anyString(), any());
        // ...ama kesici bunu TEK mantiksal basarisizlik saydi.
        //
        // Sarma sirasi bu yuzden onemli: Retry, CircuitBreaker'in ICINDE.
        // Disinda olsaydi her deneme ayri yazilir ve kesici uc kat hizli acilirdi.
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("kesici açıkken sağlayıcıya hiç gidilmez")
    void openBreakerStopsCallsReachingTheProvider() {
        when(delegate.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentProviderUnavailableException("baglanti reddedildi", false));

        for (int i = 0; i < 4; i++) {
            String orderId = "order-" + i;
            assertThatThrownBy(() -> charge(orderId))
                    .isInstanceOf(PaymentProviderUnavailableException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        org.mockito.Mockito.reset(delegate);

        assertThatThrownBy(() -> charge("order-x"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageContaining("devre kesici açık");

        // Asil koruma: coken saglayiciya yuk binmeye DEVAM ETMEZ.
        verify(delegate, never()).charge(anyString(), anyString(), any());
    }
}
