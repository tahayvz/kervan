package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class OutboxRepositoryAdapter implements OutboxRepository {

    private final SpringDataOutboxRepository repository;

    OutboxRepositoryAdapter(SpringDataOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        UUID id = message.id() == null ? UUID.randomUUID() : UUID.fromString(message.id());

        OutboxEntity saved = repository.save(new OutboxEntity(
                id,
                message.aggregateType(),
                message.aggregateId(),
                message.eventType(),
                message.payload(),
                message.occurredAt(),
                message.publishedAt()));

        return toDomain(saved);
    }

    @Override
    public List<OutboxMessage> findUnpublished(int limit) {
        return repository.findByPublishedAtIsNullOrderByOccurredAtAsc(Limit.of(limit))
                .stream()
                .map(OutboxRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public void markPublished(String id, Instant publishedAt) {
        repository.findById(UUID.fromString(id)).ifPresent(entity ->
                repository.save(new OutboxEntity(
                        entity.getId(),
                        entity.getAggregateType(),
                        entity.getAggregateId(),
                        entity.getEventType(),
                        entity.getPayload(),
                        entity.getOccurredAt(),
                        publishedAt)));
    }

    private static OutboxMessage toDomain(OutboxEntity entity) {
        return new OutboxMessage(
                entity.getId().toString(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getOccurredAt(),
                entity.getPublishedAt());
    }
}
