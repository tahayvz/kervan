package com.kervan.payment.infrastructure.messaging;

import com.kervan.contracts.payment.v1.ProcessPayment;
import com.kervan.contracts.payment.v1.RefundPayment;
import com.kervan.payment.application.PaymentProcessingService;
import com.kervan.payment.domain.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Saga'nın ödeme komutlarını dinler.
 *
 * <p>Konuda birden fazla mesaj tipi var; aynı konuda durmalarının sebebi sıra
 * garantisidir (ADR-0009). Sınıf düzeyinde dinleyici + {@code @KafkaHandler} gelen
 * mesajı tipine göre doğru metoda yönlendirir.
 *
 * <p>groupId burada verilmez: tek kaynak {@code spring.kafka.consumer.group-id}.
 */
@Component
@KafkaListener(topics = "${kervan.payment.commands-topic}")
class PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    private final PaymentProcessingService payments;

    PaymentCommandListener(PaymentProcessingService payments) {
        this.payments = payments;
    }

    @KafkaHandler
    void on(ProcessPayment command) {
        log.debug("ProcessPayment alındı: orderId={}", command.getOrderId());
        payments.process(command.getOrderId(), command.getCustomerId(),
                Money.of(command.getAmount(), command.getCurrency()));
    }

    @KafkaHandler
    void on(RefundPayment command) {
        log.debug("RefundPayment alındı: orderId={}", command.getOrderId());
        payments.refund(command.getOrderId());
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object message) {
        log.warn("Tanınmayan komut tipi ölü mektup konusuna gidiyor: {}",
                message.getClass().getName());
        throw new UnsupportedCommandException(message);
    }
}
