package com.kervan.payment.infrastructure.outbox;

import com.kervan.payment.domain.port.OutboxRepository;
import com.kervan.payment.infrastructure.observability.TraceParentProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
class OutboxRepositoryAdapter implements OutboxRepository {

    /** Debezium yönlendirmesinde kullanılan toplam adı (ADR-0004). */
    private static final String AGGREGATE_TYPE = "Payment";

    private final SpringDataOutboxRepository repository;
    private final TraceParentProvider traceParents;

    OutboxRepositoryAdapter(SpringDataOutboxRepository repository,
                            TraceParentProvider traceParents) {
        this.repository = repository;
        this.traceParents = traceParents;
    }

    @Override
    public void save(String aggregateId, String eventType, byte[] payload, Instant occurredAt) {
        // İzleme bağlamı burada yakalanır, çağıran kodda değil: olayı yazan iş
        // mantığının izlemeden haberi olmaması gerekir. Yakalama noktası "kaydın
        // veritabanına düştüğü an"dır; o an hâlâ isteğin iş parçacığındayız.
        repository.save(new OutboxEntity(
                UUID.randomUUID(), AGGREGATE_TYPE, aggregateId, eventType, payload, occurredAt,
                traceParents.current()));
    }
}
