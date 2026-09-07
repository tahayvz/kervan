package com.kervan.payment.infrastructure.messaging;

import com.kervan.contracts.payment.v1.PaymentFailed;
import com.kervan.contracts.payment.v1.PaymentProcessed;
import com.kervan.contracts.payment.v1.PaymentRefunded;
import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.port.OutboxRepository;
import com.kervan.payment.domain.port.PaymentEventPublisher;
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
import java.util.Map;

/**
 * Ödeme olaylarını Avro'ya çevirip outbox'a yazar.
 *
 * <p>Kafka'ya doğrudan yazılmaz: olay, tahsilatla aynı transaction'da tabloya girer ve
 * oradan Debezium taşır (ADR-0004).
 */
@Component
class AvroPaymentEventPublisher implements PaymentEventPublisher {

    /** Sözleşmedeki {@code decimal} ölçeği; ADR-0008. */
    private static final int MONEY_SCALE = 4;

    private final OutboxRepository outbox;
    private final KafkaAvroSerializer serializer;
    private final String topic;

    AvroPaymentEventPublisher(OutboxRepository outbox,
                              @Value("${kervan.payment.events-topic}") String topic,
                              @Value("${kervan.schema-registry.url}") String schemaRegistryUrl,
                              @Value("${kervan.schema-registry.auto-register:true}") boolean autoRegister) {
        this.outbox = outbox;
        this.topic = topic;
        this.serializer = new KafkaAvroSerializer();
        this.serializer.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegister,
                AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName()
        ), false);
    }

    @Override
    public void paymentProcessed(String orderId, String paymentId, Money amount, Instant at) {
        write(orderId, PaymentProcessed.newBuilder()
                .setOrderId(orderId)
                .setPaymentId(paymentId)
                .setAmount(scaled(amount))
                .setCurrency(amount.currencyCode())
                .setProcessedAt(at)
                .build(), at);
    }

    @Override
    public void paymentFailed(String orderId, String reason, Instant at) {
        write(orderId, PaymentFailed.newBuilder()
                .setOrderId(orderId)
                .setReason(reason)
                .setFailedAt(at)
                .build(), at);
    }

    @Override
    public void paymentRefunded(String orderId, String paymentId, Instant at) {
        write(orderId, PaymentRefunded.newBuilder()
                .setOrderId(orderId)
                .setPaymentId(paymentId)
                .setRefundedAt(at)
                .build(), at);
    }

    /**
     * Şemadaki ölçek sabittir; {@code Money} para biriminin hane sayısını kullandığı
     * için (KWD'de 3, TRY'de 2) ölçek burada açıkça ayarlanır.
     *
     * <p>{@code UNNECESSARY}: normal akışta ölçeği 4'e çıkarmak kayıpsızdır ve bu satır
     * hata vermez. Hata verdiği tek durum, tutarın {@code Money} kullanılmadan
     * üretilmiş olmasıdır — sessizce yuvarlayıp yanlış tutar yayınlamaktansa durmak
     * doğrudur.
     */
    private static BigDecimal scaled(Money amount) {
        return amount.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private void write(String orderId, SpecificRecord event, Instant occurredAt) {
        outbox.save(orderId, event.getSchema().getName(),
                serializer.serialize(topic, event), occurredAt);
    }

    @PreDestroy
    void close() {
        serializer.close();
    }
}
