package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.PaymentProviderUnavailableException;
import com.kervan.payment.domain.port.PaymentGateway;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Ödeme sağlayıcısına yapılan çağrıyı dayanıklılık katmanıyla sarar.
 *
 * <h2>Neden sarmalayıcı, neden anotasyon değil?</h2>
 * {@code @CircuitBreaker} anotasyonu daha kısa olurdu. Ama o zaman davranış bir
 * proxy'nin içinde gizlenirdi ve birim testinde doğrulanamazdı. Bu depoda aynı
 * gerekçeyle Java ajanı da elenmişti (ADR-0012): <b>testi olmayan davranış
 * yoktur.</b> Burada zincir açıkça kurulur ve testte aynı sınıf, sahte bir
 * sağlayıcıyla çalıştırılır.
 *
 * <h2>Sarma sırası ÖNEMLİDİR</h2>
 * <pre>
 *   Bulkhead( CircuitBreaker( Retry( gercekCagri ) ) )
 * </pre>
 *
 * İçten dışa okunur:
 * <ol>
 *   <li><b>Retry en içte:</b> tekrar denemeler tek bir "mantıksal çağrı" sayılır.
 *       Dışta olsaydı her deneme kesiciye ayrı bir başarısızlık olarak yazılır ve
 *       kesici üç kat hızlı açılırdı.</li>
 *   <li><b>CircuitBreaker ortada:</b> kesici açıkken retry hiç çalışmaz. Tersi
 *       olsaydı, çökmüş bir sağlayıcıya her istek için üç çağrı daha giderdi —
 *       yani koruma, yükü üçe katlardı.</li>
 *   <li><b>Bulkhead en dışta:</b> eş zamanlı çağrı sayısını sınırlar. Sağlayıcı
 *       yavaşladığında tüketici iş parçacıklarının tamamının orada birikmesini
 *       engeller; yavaş bir dış sistem bütün servisi yutmamalı.</li>
 * </ol>
 *
 * <h2>Reddedilme başarısızlık DEĞİLDİR</h2>
 * Kesici yalnızca {@link PaymentProviderUnavailableException}'ı sayar. Reddedilme
 * sağlayıcının çalıştığının kanıtıdır; onu saymak, meşru bir ret dalgasının
 * çalışan bir sağlayıcıya giden bütün ödemeleri kesmesi demek olurdu. Bu, ayarda
 * {@code ignoreExceptions} ile de yazılı.
 */
@Component
@Primary
class ResilientPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentGateway.class);
    static final String INSTANCE = "payment-provider";

    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    /**
     * Spring'in kullandığı kurucu.
     *
     * <p>{@code @Autowired} şart: sınıfın iki kurucusu var ve Spring hangisini
     * seçeceğini kendiliğinden bilemez. Tek kurucu olsaydı gerekmezdi.
     */
    @Autowired
    ResilientPaymentGateway(SimulatedPaymentGateway delegate,
                            CircuitBreakerRegistry circuitBreakers,
                            RetryRegistry retries,
                            BulkheadRegistry bulkheads) {
        this(delegate,
                circuitBreakers.circuitBreaker(INSTANCE),
                retries.retry(INSTANCE),
                bulkheads.bulkhead(INSTANCE));
    }

    /** Testler zinciri kendi ayarlarıyla kurabilsin diye. */
    ResilientPaymentGateway(PaymentGateway delegate, CircuitBreaker circuitBreaker,
                            Retry retry, Bulkhead bulkhead) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                // Bu satır bir arıza anında ilk bakılacak yerdir; metrikle
                // birlikte okunur (Faz 6).
                log.warn("Ödeme sağlayıcısı devre kesici durumu değişti: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    @Override
    public String charge(String orderId, String customerId, Money amount) {
        return call("charge", orderId, () -> delegate.charge(orderId, customerId, amount));
    }

    @Override
    public void refund(String orderId, String providerReference, Money amount) {
        call("refund", orderId, () -> {
            delegate.refund(orderId, providerReference, amount);
            return null;
        });
    }

    private <T> T call(String operation, String orderId, Supplier<T> action) {
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Retry.decorateSupplier(retry, action)));
        try {
            return guarded.get();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // Kesici acik: cagri HIC yapilmadi. Bunu ayri bir mesajla soylemek
            // onemli -- "saglayici reddetti" degil, "saglayiciya gidilmedi".
            log.warn("Ödeme sağlayıcısına gidilmedi, devre kesici açık: islem={} orderId={}",
                    operation, orderId);
            throw new PaymentProviderUnavailableException(
                    "Ödeme sağlayıcısı şu an erişilemiyor (devre kesici açık)", false, e);
        } catch (io.github.resilience4j.bulkhead.BulkheadFullException e) {
            log.warn("Ödeme sağlayıcısına giden eş zamanlı çağrı sınırı doldu: islem={} orderId={}",
                    operation, orderId);
            throw new PaymentProviderUnavailableException(
                    "Ödeme sağlayıcısı meşgul, çağrı sıraya alınmadı", false, e);
        }
    }
}
