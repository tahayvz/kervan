package com.kervan.order.infrastructure.messaging;

import com.kervan.contracts.inventory.v1.StockReleased;
import com.kervan.contracts.inventory.v1.StockReservationFailed;
import com.kervan.contracts.inventory.v1.StockReserved;
import com.kervan.contracts.payment.v1.PaymentFailed;
import com.kervan.contracts.payment.v1.PaymentProcessed;
import com.kervan.order.application.SagaOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Saga'yı ilerleten olayları dinler.
 *
 * <p>İki konu dinlenir: stok ve ödeme servislerinin cevapları. Her konuda birden fazla
 * olay tipi var — aynı konuda durmalarının sebebi sıra garantisidir (ADR-0009) — ve
 * sınıf düzeyinde dinleyici + {@code @KafkaHandler} gelen mesajı tipine göre doğru
 * metoda yönlendirir.
 *
 * <p>Bu sınıf karar vermez, yalnızca çevirir: hangi olayın saga'yı nereye taşıdığı
 * {@link SagaOrchestrator} ve durum makinesinin işidir. Mesajlaşma ayrıntısı ile akış
 * mantığını ayrı tutmak, akışı Kafka olmadan test edilebilir kılar.
 */
@Component
@KafkaListener(topics = {
        "${kervan.topics.inventory-events}",
        "${kervan.topics.payment-events}"
})
class SagaEventListener {

    private static final Logger log = LoggerFactory.getLogger(SagaEventListener.class);

    private final SagaOrchestrator orchestrator;

    SagaEventListener(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaHandler
    void on(StockReserved event) {
        orchestrator.onStockReserved(event.getOrderId(), event.getReservationId());
    }

    @KafkaHandler
    void on(StockReservationFailed event) {
        orchestrator.onStockReservationFailed(event.getOrderId(), event.getReason());
    }

    @KafkaHandler
    void on(StockReleased event) {
        orchestrator.onStockReleased(event.getOrderId());
    }

    @KafkaHandler
    void on(PaymentProcessed event) {
        orchestrator.onPaymentProcessed(event.getOrderId(), event.getPaymentId());
    }

    @KafkaHandler
    void on(PaymentFailed event) {
        orchestrator.onPaymentFailed(event.getOrderId(), event.getReason());
    }

    /**
     * Tanınmayan bir olay tipi kaybedilmez: ölü mektup konusuna gider ve orada görülür.
     * Sessizce atlansaydı saga cevap beklerken asılı kalır, kimse fark etmezdi.
     */
    @KafkaHandler(isDefault = true)
    void onUnknown(Object message) {
        log.warn("Tanınmayan olay tipi ölü mektup konusuna gidiyor: {}",
                message.getClass().getName());
        throw new UnsupportedSagaEventException(message);
    }
}
