package com.kervan.inventory.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataOutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}
