package com.kervan.order.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_messages")
class OutboxEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** Mesajın gideceği Kafka konusu; Debezium yönlendirmeyi buna göre yapar. */
    @Column(nullable = false)
    private String destination;

    /**
     * Avro ile serileştirilmiş olay. Postgres tarafında {@code bytea}.
     * <p>
     * {@code @Lob} bilerek kullanılmadı: PostgreSQL sürücüsünde {@code @Lob byte[]},
     * veriyi tabloya değil {@code pg_largeobject}'e yazan bir OID'ye eşlenir. O
     * durumda satır silinse bile içerik ortada kalır ve Debezium'un okuduğu WAL
     * kaydında payload'ın kendisi bulunmaz — CDC bu sütunu göremezdi.
     */
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * Kimlik uygulamada üretildiği için Spring Data, kaydı "mevcut" sanıp her
     * INSERT öncesi gereksiz bir SELECT çalıştırırdı. {@link Persistable} ile
     * yeni olup olmadığını açıkça söylüyoruz.
     */
    @Transient
    private boolean isNew = true;

    protected OutboxEntity() {
        // JPA için
    }

    OutboxEntity(UUID id, String aggregateType, String aggregateId, String eventType,
                 String destination, byte[] payload, Instant occurredAt, Instant publishedAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.destination = destination;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.publishedAt = publishedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    int getAttempts() {
        return attempts;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getAggregateId() {
        return aggregateId;
    }

    String getEventType() {
        return eventType;
    }

    String getDestination() {
        return destination;
    }

    byte[] getPayload() {
        return payload;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }
}
