package com.kervan.payment.domain.model;

/**
 * Ödeme sağlayıcısı tahsilatı reddetti.
 *
 * <p>Programlama hatası değil, işin normal bir sonucudur: kart limiti dolar, hesap
 * kapanır. Saga bunu bir başarısızlık adımı sayar ve önceki adımları telafi eder.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String reason) {
        super(reason);
    }
}
