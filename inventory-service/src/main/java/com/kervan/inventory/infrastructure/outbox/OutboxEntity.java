package com.kervan.inventory.infrastructure.outbox;

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

    /**
     * Avro ile serileştirilmiş olay; Postgres tarafında {@code bytea}.
     * {@code @Lob} bilerek kullanılmadı — gerekçesi order-service'teki eşdeğerinde:
     * OID'ye eşlenen içerik WAL kaydında görünmez ve Debezium sütunu okuyamaz.
     */
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean isNew = true;

    protected OutboxEntity() {
        // JPA için
    }

    OutboxEntity(UUID id, String aggregateType, String aggregateId,
                 String eventType, byte[] payload, Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
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
}
