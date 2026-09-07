package com.kervan.inventory.infrastructure.outbox;

import com.kervan.inventory.domain.port.OutboxRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
class OutboxRepositoryAdapter implements OutboxRepository {

    /** Debezium yönlendirmesinde kullanılan toplam adı (ADR-0004). */
    private static final String AGGREGATE_TYPE = "Inventory";

    private final SpringDataOutboxRepository repository;

    OutboxRepositoryAdapter(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(String aggregateId, String eventType, byte[] payload, Instant occurredAt) {
        repository.save(new OutboxEntity(
                UUID.randomUUID(), AGGREGATE_TYPE, aggregateId, eventType, payload, occurredAt));
    }
}
