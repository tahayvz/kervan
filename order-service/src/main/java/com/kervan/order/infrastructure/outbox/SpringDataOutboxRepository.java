package com.kervan.order.infrastructure.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataOutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /** Yayınlanmamış kayıtlar, oluşma sırasıyla. */
    List<OutboxEntity> findByPublishedAtIsNullOrderByOccurredAtAsc(Limit limit);
}
