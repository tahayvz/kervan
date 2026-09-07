package com.kervan.payment.domain.port;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.PaymentDeclinedException;

/**
 * Parayı gerçekten tahsil eden dış sistem.
 *
 * <p><b>Bu projede gerçek bir sağlayıcı yok.</b> Uygulaması, davranışı testte
 * belirlenebilsin diye kural tabanlı bir taklittir ({@code SimulatedPaymentGateway}).
 * Port yine de var, çünkü asıl mesele burada: dış sistem çağrısı bir <em>port</em>
 * arkasında durursa, iş kuralları o sistem olmadan test edilebilir ve sağlayıcı
 * değiştiğinde yalnızca tek bir sınıf değişir.
 *
 * <p>Saga'nın telafi mantığı, tahsilatın gerçekten yapılıp yapılmadığından bağımsız
 * olarak doğrudur; taklit olması o mantığı değersizleştirmez.
 */
public interface PaymentGateway {

    /**
     * Tutarı tahsil eder.
     *
     * @return sağlayıcının verdiği işlem kimliği
     * @throws PaymentDeclinedException sağlayıcı reddederse — bu bir hata değil, işin
     *                                  normal bir sonucudur
     */
    String charge(String orderId, String customerId, Money amount);

    /** Tahsil edilmiş tutarı iade eder (Saga telafisi). */
    void refund(String orderId, String providerReference, Money amount);
}
