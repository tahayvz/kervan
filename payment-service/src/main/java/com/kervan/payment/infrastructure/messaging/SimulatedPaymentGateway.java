package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.PaymentDeclinedException;
import com.kervan.payment.domain.port.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ödeme sağlayıcısının taklidi.
 *
 * <p><b>Bu gerçek bir tahsilat yapmaz ve öyleymiş gibi de görünmemeli.</b> Projede
 * bağlanılacak bir sağlayıcı yok; buradaki amaç saga'nın başarı ve başarısızlık
 * yollarının ikisini de çalıştırabilmek.
 *
 * <p>Davranış <b>kurala</b> bağlı, rastgele değil: belirlenen sınırın üstündeki tutar
 * reddedilir. Rastgele olsaydı testler ara sıra kırılır ve kırılma sebebi kodda
 * aranırdı; asıl mesele saga'nın doğruluğu, sağlayıcının kaprisi değil.
 *
 * <p>Gerçek bir sağlayıcı geldiğinde değişecek tek sınıf budur — port sayesinde iş
 * kuralları ve testleri olduğu gibi kalır.
 */
@Component
class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final BigDecimal declineAbove;

    SimulatedPaymentGateway(@Value("${kervan.payment.simulator.decline-above}") BigDecimal declineAbove) {
        this.declineAbove = declineAbove;
    }

    @Override
    public String charge(String orderId, String customerId, Money amount) {
        if (amount.amount().compareTo(declineAbove) > 0) {
            throw new PaymentDeclinedException(
                    "Tutar sağlayıcı sınırını aşıyor: %s %s".formatted(
                            amount.amount(), amount.currencyCode()));
        }
        String reference = "SIM-" + UUID.randomUUID();
        log.info("[TAKLIT] Tahsilat yapıldı: orderId={} tutar={} {} ref={}",
                orderId, amount.amount(), amount.currencyCode(), reference);
        return reference;
    }

    @Override
    public void refund(String orderId, String providerReference, Money amount) {
        log.info("[TAKLIT] İade yapıldı: orderId={} ref={} tutar={} {}",
                orderId, providerReference, amount.amount(), amount.currencyCode());
    }
}
