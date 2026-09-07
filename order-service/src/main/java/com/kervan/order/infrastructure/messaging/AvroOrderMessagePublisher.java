package com.kervan.order.infrastructure.messaging;

import com.kervan.contracts.inventory.v1.ReleaseStock;
import com.kervan.contracts.inventory.v1.ReservationItem;
import com.kervan.contracts.inventory.v1.ReserveStock;
import com.kervan.contracts.order.v1.OrderCancelled;
import com.kervan.contracts.order.v1.OrderConfirmed;
import com.kervan.contracts.payment.v1.ProcessPayment;
import com.kervan.contracts.payment.v1.RefundPayment;
import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.OrderLine;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OrderMessagePublisher;
import com.kervan.order.domain.port.OutboxRepository;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import jakarta.annotation.PreDestroy;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Mesajları Avro'ya çevirip outbox'a yazar.
 *
 * <p>Kafka'ya doğrudan yazılmaz: mesaj, onu doğuran iş verisiyle aynı transaction'da
 * tabloya girer ve oradan taşınır (ADR-0004). Saga için bu şart — durum değişikliği
 * ile onu ilerleten komut ya birlikte olur ya hiç olmaz.
 *
 * <p>Her mesajın hedef konusu satıra yazılır. Subject adı da o konudan türer
 * ({@code <konu>-<kayıt adı>}), bu yüzden serileştirme hedefi bilmek zorunda.
 */
@Component
class AvroOrderMessagePublisher implements OrderMessagePublisher {

    private static final String AGGREGATE_TYPE = "Order";

    /** Sözleşmedeki {@code decimal} ölçeği (ADR-0008). */
    private static final int MONEY_SCALE = 4;

    private final OutboxRepository outbox;
    private final KafkaAvroSerializer serializer;
    private final String orderEvents;
    private final String inventoryCommands;
    private final String paymentCommands;

    AvroOrderMessagePublisher(OutboxRepository outbox,
                              @Value("${kervan.topics.order-events}") String orderEvents,
                              @Value("${kervan.topics.inventory-commands}") String inventoryCommands,
                              @Value("${kervan.topics.payment-commands}") String paymentCommands,
                              @Value("${kervan.schema-registry.url}") String schemaRegistryUrl,
                              @Value("${kervan.schema-registry.auto-register:true}") boolean autoRegister) {
        this.outbox = outbox;
        this.orderEvents = orderEvents;
        this.inventoryCommands = inventoryCommands;
        this.paymentCommands = paymentCommands;
        this.serializer = new KafkaAvroSerializer();
        this.serializer.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegister,
                // Bu konuların her birinde birden fazla mesaj tipi var; varsayılan
                // strateji konu başına tek şema kabul ederdi (ADR-0009).
                AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName()
        ), false);
    }

    @Override
    public void orderPlaced(OrderPlaced event, Instant at) {
        write(event.orderId(), orderEvents, OrderPlacedAvroMapper.toAvro(event), at);
    }

    @Override
    public void orderConfirmed(String orderId, Instant at) {
        write(orderId, orderEvents, OrderConfirmed.newBuilder()
                .setOrderId(orderId)
                .setConfirmedAt(at)
                .build(), at);
    }

    @Override
    public void orderCancelled(String orderId, String reason, Instant at) {
        write(orderId, orderEvents, OrderCancelled.newBuilder()
                .setOrderId(orderId)
                .setReason(reason)
                .setCancelledAt(at)
                .build(), at);
    }

    @Override
    public void reserveStock(String orderId, List<OrderLine> lines, Instant at) {
        write(orderId, inventoryCommands, ReserveStock.newBuilder()
                .setOrderId(orderId)
                .setItems(lines.stream()
                        .map(line -> ReservationItem.newBuilder()
                                .setProductId(line.productId())
                                .setSku(line.sku())
                                .setQuantity(line.quantity())
                                .build())
                        .toList())
                .setRequestedAt(at)
                .build(), at);
    }

    @Override
    public void releaseStock(String orderId, String reservationId, Instant at) {
        write(orderId, inventoryCommands, ReleaseStock.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setRequestedAt(at)
                .build(), at);
    }

    @Override
    public void processPayment(String orderId, String customerId, Money amount, Instant at) {
        write(orderId, paymentCommands, ProcessPayment.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(customerId)
                .setAmount(scaled(amount))
                .setCurrency(amount.currency().getCurrencyCode())
                .setRequestedAt(at)
                .build(), at);
    }

    @Override
    public void refundPayment(String orderId, String paymentId, Money amount, Instant at) {
        write(orderId, paymentCommands, RefundPayment.newBuilder()
                .setOrderId(orderId)
                .setPaymentId(paymentId)
                .setAmount(scaled(amount))
                .setCurrency(amount.currency().getCurrencyCode())
                .setRequestedAt(at)
                .build(), at);
    }

    /**
     * Şemadaki ölçek sabittir; {@code Money} para biriminin hane sayısını kullandığı
     * için ölçek burada açıkça ayarlanır. {@code UNNECESSARY}: normal akışta ölçeği
     * büyütmek kayıpsızdır. Hata verdiği tek durum tutarın {@code Money} kullanılmadan
     * üretilmiş olmasıdır — sessizce yuvarlayıp yanlış tutar yayınlamaktansa durmak
     * doğrudur.
     */
    private static BigDecimal scaled(Money amount) {
        return amount.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private void write(String orderId, String destination, SpecificRecord message, Instant at) {
        outbox.save(OutboxMessage.pending(
                AGGREGATE_TYPE, orderId, message.getSchema().getName(), destination,
                serializer.serialize(destination, message), at));
    }

    @PreDestroy
    void close() {
        serializer.close();
    }
}
