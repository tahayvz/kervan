package com.kervan.payment.application;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.Payment;
import com.kervan.payment.domain.model.PaymentDeclinedException;
import com.kervan.payment.domain.port.PaymentEventPublisher;
import com.kervan.payment.domain.port.PaymentGateway;
import com.kervan.payment.domain.port.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Saga'nın ödeme adımı.
 *
 * <h2>İdempotentlik</h2>
 * Teslimat <b>en az bir kez</b>'dir; aynı komut iki kez gelebilir. İkinci gelişte
 * müşteriden ikinci kez tahsilat yapılmamalıdır — bu, stok fazlası ayırmaktan daha
 * ciddi bir hatadır.
 *
 * <p>Ayrı bir "işlenmiş mesajlar" tablosu yok: işin doğal anahtarı zaten var, bir
 * siparişin en fazla bir ödemesi olur ve bu kural veritabanında kısıt olarak duruyor.
 * Önce mevcut ödeme aranır (normal durum), yarış hâlinde kısıt devreye girer.
 *
 * <p>Erken çıkış bir hızlandırma değildir: kaldırıldığında tekrar gelen komut kısıta
 * takılıp istisna fırlatır, dinleyici offset'i ilerletmez ve aynı mesaj sonsuza kadar
 * döner — o partition'daki her şeyle birlikte.
 *
 * <h2>Reddedilen ödeme bir hata değildir</h2>
 * Sağlayıcı reddettiğinde istisna dışarı <b>taşmaz</b>. Taşsaydı transaction geri alınır
 * ve "ödeme alınamadı" olayı da silinirdi; saga cevap beklerken asılı kalırdı. Bunun
 * yerine tahsilat kaydı hiç yazılmaz, yalnızca başarısızlık olayı yazılır.
 */
@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher events;
    private final Clock clock;

    public PaymentProcessingService(PaymentRepository payments,
                                    PaymentGateway gateway,
                                    PaymentEventPublisher events,
                                    Clock clock) {
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void process(String orderId, String customerId, Money amount) {
        Instant now = clock.instant();

        if (payments.findByOrderId(orderId).isPresent()) {
            log.debug("Ödeme zaten alınmış, komut yok sayıldı: orderId={}", orderId);
            return;
        }

        try {
            gateway.charge(orderId, customerId, amount);
        } catch (PaymentDeclinedException e) {
            log.info("Ödeme reddedildi, sipariş iptal edilecek: orderId={} sebep={}",
                    orderId, e.getMessage());
            events.paymentFailed(orderId, e.getMessage(), now);
            return;
        }

        Payment saved = payments.save(Payment.captured(orderId, customerId, amount, now));

        events.paymentProcessed(orderId, saved.id(), amount, now);
        log.info("Ödeme alındı: orderId={} paymentId={}", orderId, saved.id());
    }

    /** Telafi: alınmış ödemeyi iade eder. */
    @Transactional
    public void refund(String orderId) {
        Instant now = clock.instant();

        Optional<Payment> found = payments.findByOrderId(orderId);
        if (found.isEmpty()) {
            // Tahsilat hiç yapılmamış (örneğin sağlayıcı baştan reddetmişti). İade
            // komutu bu durumda da gelebilir ve hata değildir.
            log.debug("İade edilecek ödeme yok: orderId={}", orderId);
            return;
        }

        Payment payment = found.get();
        if (!payment.isCaptured()) {
            // İade komutu ikinci kez geldi. Tekrar iade etmek müşteriye ikinci kez
            // para göndermek olurdu.
            log.debug("Ödeme zaten iade edilmiş: orderId={}", orderId);
            return;
        }

        gateway.refund(orderId, payment.id(), payment.amount());
        payments.save(payment.refunded(now));

        events.paymentRefunded(orderId, payment.id(), now);
        log.info("Ödeme iade edildi: orderId={} paymentId={}", orderId, payment.id());
    }
}
