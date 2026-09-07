package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class OutboxRepositoryAdapter implements OutboxRepository {

    /** Hata metni sütuna sığsın; asıl yığın izi zaten loglanıyor. */
    private static final int MAX_ERROR_LENGTH = 500;

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
                message.destination(),
                message.payload(),
                message.occurredAt(),
                message.publishedAt()));

        return toDomain(saved);
    }

    @Override
    public List<OutboxMessage> lockDeliverable(int limit, int maxAttempts) {
        return repository.lockDeliverable(maxAttempts, Limit.of(limit))
                .stream()
                .map(OutboxRepositoryAdapter::toDomain)
                .toList();
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
        repository.markPublished(UUID.fromString(id), publishedAt);
    }

    @Override
    public void recordFailedAttempt(String id, Instant at, String error) {
        repository.recordFailedAttempt(UUID.fromString(id), at, truncate(error));
    }

    /**
     * Silme iki ifadeye bölünmüştür (önce kimlikleri seç, sonra sil) ve bu ikisi tek
     * transaction'da olmak zorundadır. JPA, transaction dışında değiştirici sorgu
     * çalıştırmayı zaten reddeder; ayrıca araya giren bir işlem seçilmiş kimlikleri
     * değiştirebilirdi.
     *
     * <p>Çağıran kendi transaction'ını açmışsa Spring ona katılır. Yani doğruluk
     * çağıranın dikkatine bırakılmamıştır.
     */
    @Override
    @Transactional
    public int deletePublishedBefore(Instant cutoff, int batchSize) {
        return deleteExpired(cutoff, true, batchSize);
    }

    /** Gerekçe: {@link #deletePublishedBefore}. */
    @Override
    @Transactional
    public int deleteAllBefore(Instant cutoff, int batchSize) {
        return deleteExpired(cutoff, false, batchSize);
    }

    private int deleteExpired(Instant cutoff, boolean publishedOnly, int batchSize) {
        List<UUID> ids = repository.findExpiredIds(cutoff, publishedOnly, Limit.of(batchSize));
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.deleteByIds(ids);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static OutboxMessage toDomain(OutboxEntity entity) {
        return new OutboxMessage(
                entity.getId().toString(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getDestination(),
                entity.getPayload(),
                entity.getOccurredAt(),
                entity.getPublishedAt());
    }
}
