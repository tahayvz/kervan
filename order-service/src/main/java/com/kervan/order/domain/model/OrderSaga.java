package com.kervan.order.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bir siparişin saga'sı: hangi adımda olduğu ve telafi için gereken kimlikler.
 *
 * <h2>Neden kalıcı?</h2>
 * Saga uzun ömürlüdür; adımlar arasında dakikalar geçebilir ve servis bu sırada
 * yeniden başlayabilir. Durum bellekte tutulsaydı her yeniden başlatma devam eden
 * bütün siparişleri unuturdu.
 *
 * <h2>Neden kimlikler saklanıyor?</h2>
 * Telafi, yapılanı geri almaktır ve geri almak için <b>neyin</b> yapıldığını bilmek
 * gerekir. Stok geri bırakılırken hangi ayırmanın, ödeme iade edilirken hangi
 * tahsilatın olduğu bunlarla bilinir; sipariş kimliği tek başına yetmez çünkü aynı
 * sipariş için birden fazla deneme olabilir.
 */
public record OrderSaga(
        String orderId,
        SagaState state,
        String reservationId,
        String paymentId,
        Instant createdAt,
        Instant updatedAt) {

    public OrderSaga {
        Objects.requireNonNull(orderId, "orderId null olamaz");
        Objects.requireNonNull(state, "state null olamaz");
        Objects.requireNonNull(createdAt, "createdAt null olamaz");
    }

    /** Sipariş alındı; ilk adım stok ayırma. */
    public static OrderSaga started(String orderId, Instant now) {
        return new OrderSaga(orderId, SagaState.STOCK_RESERVING, null, null, now, now);
    }

    public Optional<String> reservation() {
        return Optional.ofNullable(reservationId);
    }

    public Optional<String> payment() {
        return Optional.ofNullable(paymentId);
    }

    /**
     * Saga'yı bir sonraki duruma taşır.
     *
     * @throws InvalidSagaTransitionException geçiş izinli değilse — tekrar gelen ya da
     *                                        sırasız bir olayın saga'yı geri sarmasını
     *                                        ya da iki kez ilerletmesini engeller
     */
    public OrderSaga transitionTo(SagaState next, Instant now) {
        if (!state.canTransitionTo(next)) {
            throw new InvalidSagaTransitionException(orderId, state, next);
        }
        return new OrderSaga(orderId, next, reservationId, paymentId, createdAt, now);
    }

    public OrderSaga withReservation(String reservationId) {
        return new OrderSaga(orderId, state, reservationId, paymentId, createdAt, updatedAt);
    }

    public OrderSaga withPayment(String paymentId) {
        return new OrderSaga(orderId, state, reservationId, paymentId, createdAt, updatedAt);
    }
}
