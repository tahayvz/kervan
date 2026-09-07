package com.kervan.inventory.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    Optional<ReservationEntity> findByOrderId(String orderId);
}
