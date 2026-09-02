package com.kervan.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataOrderRepository extends JpaRepository<OrderEntity, UUID> {
}
