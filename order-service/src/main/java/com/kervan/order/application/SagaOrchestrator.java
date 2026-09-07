package com.kervan.order.application;

import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.model.InvalidSagaTransitionException;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.model.OrderStatus;
import com.kervan.order.domain.model.SagaState;
import com.kervan.order.domain.port.OrderMessagePublisher;
import com.kervan.order.domain.port.OrderRepository;
import com.kervan.order.domain.port.SagaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Sipariş saga'sını yürütür.
 *
 * <h2>Akış</h2>
 * <pre>
 *   sipariş alındı ──▶ ReserveStock
 *                        │
 *          StockReserved ┤                    StockReservationFailed
 *                        ▼                              │
 *                  ProcessPayment                       ▼
 *                        │                        sipariş İPTAL
 *      PaymentProcessed  ┤  PaymentFailed
 *              ▼         │        ▼
 *        sipariş ONAY    │   ReleaseStock ──▶ StockReleased ──▶ sipariş İPTAL
 * </pre>
 *
 * <h2>Neden orchestration, choreography değil?</h2>
 * Akışın tamamı tek bir yerde okunur. Choreography'de her servis bir sonrakini
 * tetikler ve "sipariş neden iptal oldu" sorusunun cevabı beş servise dağılır.
 * Telafi eden bir akışta bu, hata ayıklamayı imkânsıza yakın kılar (ADR-0005).
 *
 * <h2>Tekrar gelen olaylar</h2>
 * Teslimat <b>en az bir kez</b>'dir. Her adım saga'yı kilitleyerek okur ve geçişin
 * izinli olup olmadığına {@link SagaState} karar verir. İzinsiz bir geçiş, olayın
 * zaten işlendiği anlamına gelir; hata değildir, yok sayılır.
 *
 * <p>Kilit olmadan aynı siparişin iki olayı yan yana işlenebilir ve aynı komut iki kez
 * gönderilebilirdi — örneğin ikinci kez ödeme istenirdi.
 *
 * <h2>Her adım tek transaction</h2>
 * Durum değişikliği ile onu ilerleten komut aynı commit'te yazılır. Ayrı olsalardı
 * araya giren bir çökme saga'yı sonsuza kadar asılı bırakırdı: durum "ödeme
 * bekleniyor" der ama kimseye ödeme komutu gitmemiştir.
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final SagaRepository sagas;
    private final OrderRepository orders;
    private final OrderMessagePublisher messages;
    private final Clock clock;

    public SagaOrchestrator(SagaRepository sagas,
                            OrderRepository orders,
                            OrderMessagePublisher messages,
                            Clock clock) {
        this.sagas = sagas;
        this.orders = orders;
        this.messages = messages;
        this.clock = clock;
    }

    /** Stok ayrıldı → ödemeyi iste. */
    @Transactional
    public void onStockReserved(String orderId, String reservationId) {
        advance(orderId, SagaState.PAYMENT_PROCESSING, (saga, now) -> {
            Order order = requireOrder(orderId);
            messages.processPayment(orderId, order.customerId(), order.totalAmount(), now);
            return saga.withReservation(reservationId);
        });
    }

    /** Stok yetmedi → geri alınacak bir şey yok, siparişi iptal et. */
    @Transactional
    public void onStockReservationFailed(String orderId, String reason) {
        advance(orderId, SagaState.CANCELLED, (saga, now) -> {
            cancelOrder(orderId, reason, now);
            return saga;
        });
    }

    /** Ödeme alındı → siparişi onayla, saga biter. */
    @Transactional
    public void onPaymentProcessed(String orderId, String paymentId) {
        advance(orderId, SagaState.COMPLETED, (saga, now) -> {
            Order order = requireOrder(orderId);
            order.changeStatus(OrderStatus.CONFIRMED, now);
            orders.save(order);
            messages.orderConfirmed(orderId, now);
            log.info("Saga tamamlandı: orderId={}", orderId);
            return saga.withPayment(paymentId);
        });
    }

    /**
     * Ödeme başarısız → <b>telafi</b>: tutulan stok geri bırakılmalı.
     *
     * <p>Sipariş burada henüz iptal edilmez. Telafi tamamlanmadan iptal etmek,
     * müşteriye "iptal edildi" derken stoğun hâlâ tutuluyor olması demekti.
     */
    @Transactional
    public void onPaymentFailed(String orderId, String reason) {
        advance(orderId, SagaState.STOCK_RELEASING, (saga, now) -> {
            String reservationId = saga.reservation().orElseThrow(() -> new IllegalStateException(
                    "Telafi edilecek ayırma kimliği yok: orderId=" + orderId));
            messages.releaseStock(orderId, reservationId, now);
            log.info("Ödeme başarısız, stok geri bırakılıyor: orderId={} sebep={}", orderId, reason);
            return saga;
        });
    }

    /** Telafi tamamlandı → siparişi iptal et. */
    @Transactional
    public void onStockReleased(String orderId) {
        advance(orderId, SagaState.CANCELLED, (saga, now) -> {
            cancelOrder(orderId, "Ödeme alınamadı", now);
            return saga;
        });
    }

    /**
     * Saga'yı kilitler, geçişin izinli olduğunu doğrular ve adımı uygular.
     *
     * <p>İki durumda sessizce çıkılır ve bunların ikisi de <b>beklenen</b> durumdur:
     * saga bulunamazsa (bu servise ait olmayan bir sipariş) ve geçiş izinli değilse
     * (olay tekrar gelmiş). Tekrar gelen olayda istisna fırlatmak, mesajın sonsuza
     * kadar yeniden teslim edilmesine yol açardı.
     */
    private void advance(String orderId, SagaState next, SagaStep step) {
        Instant now = clock.instant();

        Optional<OrderSaga> found = sagas.lockByOrderId(orderId);
        if (found.isEmpty()) {
            log.warn("Saga bulunamadı, olay yok sayıldı: orderId={}", orderId);
            return;
        }

        OrderSaga saga = found.get();
        if (!saga.state().canTransitionTo(next)) {
            log.debug("Geçiş izinli değil, olay zaten işlenmiş olmalı: orderId={} {} -> {}",
                    orderId, saga.state(), next);
            return;
        }

        try {
            sagas.save(step.apply(saga, now).transitionTo(next, now));
        } catch (InvalidSagaTransitionException e) {
            // canTransitionTo ile zaten kontrol edildi; buraya düşmek durumun adım
            // içinde değiştiği anlamına gelir ve bu bir programlama hatasıdır.
            throw new IllegalStateException("Saga geçişi beklenmedik şekilde reddedildi", e);
        }
    }

    private void cancelOrder(String orderId, String reason, Instant now) {
        Order order = requireOrder(orderId);
        order.changeStatus(OrderStatus.CANCELLED, now);
        orders.save(order);
        messages.orderCancelled(orderId, reason, now);
        log.info("Sipariş iptal edildi: orderId={} sebep={}", orderId, reason);
    }

    private Order requireOrder(String orderId) {
        return orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /** Adımın kendi işi; saga'nın güncellenmiş hâlini döner. */
    @FunctionalInterface
    private interface SagaStep {
        OrderSaga apply(OrderSaga saga, Instant now);
    }
}
