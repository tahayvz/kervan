package com.kervan.payment.domain.port;

import com.kervan.payment.domain.model.Money;

import java.time.Instant;

/**
 * Ödeme tarafının duyurduğu olaylar.
 *
 * <p>Uygulama katmanı olayın hangi biçimde ve nereye yazıldığını bilmez; burada yalnızca
 * "şu oldu" denir. Niyet bildiren metotlar, hangi olayların var olduğunu tek bakışta
 * görünür kılar.
 */
public interface PaymentEventPublisher {

    void paymentProcessed(String orderId, String paymentId, Money amount, Instant at);

    /**
     * @param reason insan için açıklama; saga içeriğine göre dallanmaz, hangi sebeple
     *               olursa olsun önceki adımlar telafi edilir
     */
    void paymentFailed(String orderId, String reason, Instant at);

    void paymentRefunded(String orderId, String paymentId, Instant at);
}
