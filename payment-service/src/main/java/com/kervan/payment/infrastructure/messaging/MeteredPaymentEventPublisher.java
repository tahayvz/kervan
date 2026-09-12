package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.port.PaymentEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ödeme sonuçlarını sayar.
 *
 * <p>Stok tarafındaki eşdeğeriyle aynı gerekçe: sayaç uygulama katmanına değil,
 * olay yayınının etrafına sarılmış bir katmana konur. Ölçülen şey "metot çağrıldı"
 * değil "olay yazıldı"dır; tekrar gelen bir komut olay yazmaz, dolayısıyla sayılmaz.
 *
 * <h2>Ne cevaplıyor?</h2>
 * Reddedilen ödemelerin oranı. Bu oranın ani yükselişi, bu projede simülatörün
 * kuralını ({@code decline-above}) gösterir; gerçek bir sistemde ödeme sağlayıcısında
 * ya da kart tarafında bir sorun demektir. İkisinde de hiçbir servis hata vermez —
 * ödeme reddi bir arıza değil, bir sonuçtur. Görünür olmasının tek yolu budur.
 *
 * <p><b>Tutar metrik olarak yayınlanmıyor.</b> Ölçülen şey sayıdır. Para toplamını
 * metrik deposunda tutmak, kesin olması gereken bir veriyi örneklenebilen ve
 * budanabilen bir depoya taşımak olurdu; muhasebenin kaynağı veritabanıdır.
 */
@Component
@Primary
class MeteredPaymentEventPublisher implements PaymentEventPublisher {

    private static final String METRIC = "kervan.payments";

    private final PaymentEventPublisher delegate;
    private final Counter captured;
    private final Counter declined;
    private final Counter refunded;

    MeteredPaymentEventPublisher(AvroPaymentEventPublisher delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.captured = counter(registry, "captured");
        this.declined = counter(registry, "declined");
        this.refunded = counter(registry, "refunded");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder(METRIC)
                .description("Odeme sonuclari")
                .tag("result", result)
                .register(registry);
    }

    @Override
    public void paymentProcessed(String orderId, String paymentId, Money amount, Instant at) {
        delegate.paymentProcessed(orderId, paymentId, amount, at);
        captured.increment();
    }

    @Override
    public void paymentFailed(String orderId, String reason, Instant at) {
        delegate.paymentFailed(orderId, reason, at);
        declined.increment();
    }

    @Override
    public void paymentRefunded(String orderId, String paymentId, Instant at) {
        delegate.paymentRefunded(orderId, paymentId, at);
        refunded.increment();
    }
}
