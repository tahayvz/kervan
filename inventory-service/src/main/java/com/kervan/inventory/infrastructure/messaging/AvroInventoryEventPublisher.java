package com.kervan.inventory.infrastructure.messaging;

import com.kervan.contracts.inventory.v1.StockReleased;
import com.kervan.contracts.inventory.v1.StockReservationFailed;
import com.kervan.contracts.inventory.v1.StockReserved;
import com.kervan.inventory.domain.port.InventoryEventPublisher;
import com.kervan.inventory.domain.port.OutboxRepository;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import jakarta.annotation.PreDestroy;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Stok olaylarını Avro'ya çevirip outbox'a yazar.
 *
 * <p>Kafka'ya doğrudan yazılmaz: olay, stok değişikliğiyle aynı transaction'da
 * tabloya girer ve oradan Debezium taşır (ADR-0004). Bu sınıf transaction'ın içinde
 * çalışır; commit olmazsa olay da yazılmamış olur.
 *
 * <p>Subject adlandırma stratejisi {@code TopicRecordNameStrategy}: bu konuda üç
 * farklı olay tipi var ve varsayılan strateji konu başına tek şema kabul ederdi
 * (ADR-0009).
 */
@Component
class AvroInventoryEventPublisher implements InventoryEventPublisher {

    private final OutboxRepository outbox;
    private final KafkaAvroSerializer serializer;
    private final String topic;

    AvroInventoryEventPublisher(OutboxRepository outbox,
                                @Value("${kervan.inventory.events-topic}") String topic,
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
    public void stockReserved(String orderId, String reservationId, Instant at) {
        write(orderId, StockReserved.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setReservedAt(at)
                .build(), at);
    }

    @Override
    public void stockReservationFailed(String orderId, String reason, Instant at) {
        write(orderId, StockReservationFailed.newBuilder()
                .setOrderId(orderId)
                .setReason(reason)
                .setFailedAt(at)
                .build(), at);
    }

    @Override
    public void stockReleased(String orderId, String reservationId, Instant at) {
        write(orderId, StockReleased.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setReleasedAt(at)
                .build(), at);
    }

    /**
     * Outbox satırının {@code aggregate_id}'si sipariş kimliğidir; Debezium onu Kafka
     * anahtarı yapar. Böylece bir siparişin bütün olayları aynı partition'a düşer ve
     * sırası korunur (ADR-0009).
     *
     * <p>{@code occurredAt} çağırandan gelir, burada {@code Instant.now()} çağrılmaz:
     * olayın içindeki zaman damgası ile satırın zamanı aynı olmalı. İkisi ayrı
     * kaynaklardan gelseydi testte saat sabitlense bile satır sabitlenmezdi.
     */
    private void write(String orderId, SpecificRecord event, Instant occurredAt) {
        String eventType = event.getSchema().getName();
        outbox.save(orderId, eventType, serializer.serialize(topic, event), occurredAt);
    }

    @PreDestroy
    void close() {
        serializer.close();
    }
}
