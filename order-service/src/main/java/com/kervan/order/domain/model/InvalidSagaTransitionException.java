package com.kervan.order.domain.model;

/**
 * Saga'nın izin vermediği bir geçiş denendi.
 *
 * <p>Genellikle tekrar gelmiş bir olayın işaretidir: teslimat en az bir kezdir ve
 * saga zaten ilerlemiştir. Çağıran taraf bunu hata değil, "bu olay zaten işlendi"
 * diye ele almalıdır.
 */
public class InvalidSagaTransitionException extends RuntimeException {

    public InvalidSagaTransitionException(String orderId, SagaState from, SagaState to) {
        super("Saga geçişi geçersiz: orderId=%s %s -> %s".formatted(orderId, from, to));
    }
}
