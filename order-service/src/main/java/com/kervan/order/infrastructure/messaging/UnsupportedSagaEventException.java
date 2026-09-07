package com.kervan.order.infrastructure.messaging;

/**
 * Bu sürümün tanımadığı bir saga olayı geldi.
 *
 * <p>Yeniden denenmez — beklemekle tanınır hâle gelmez. Doğrudan ölü mektup konusuna
 * gider.
 */
public class UnsupportedSagaEventException extends RuntimeException {

    UnsupportedSagaEventException(Object message) {
        super("Tanınmayan saga olayı: " + message.getClass().getName());
    }
}
