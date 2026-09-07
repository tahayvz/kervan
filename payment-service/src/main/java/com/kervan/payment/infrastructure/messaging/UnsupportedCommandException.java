package com.kervan.payment.infrastructure.messaging;

/**
 * Bu sürümün tanımadığı bir komut tipi geldi.
 *
 * <p>Yeniden denenmez — beklemekle tanınır hâle gelmez. Doğrudan ölü mektup konusuna
 * gider; sessizce atlansaydı saga cevap beklerken asılı kalır ve kimse fark etmezdi.
 */
public class UnsupportedCommandException extends RuntimeException {

    UnsupportedCommandException(Object message) {
        super("Tanınmayan komut tipi: " + message.getClass().getName());
    }
}
