package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.Reservation;
import com.kervan.inventory.domain.model.ReservationLine;
import com.kervan.inventory.domain.model.ReservationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
class ReservationEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "reservation_id", nullable = false)
    private List<ReservationLineEntity> lines = new ArrayList<>();

    /**
     * Kimlik uygulamada üretildiği için Spring Data kaydı "mevcut" sanıp her INSERT
     * öncesi gereksiz bir SELECT çalıştırırdı.
     */
    @Transient
    private boolean isNew = true;

    protected ReservationEntity() {
        // JPA için
    }

    ReservationEntity(UUID id, Reservation reservation) {
        this.id = id;
        this.orderId = reservation.orderId();
        this.status = reservation.status();
        this.createdAt = reservation.createdAt();
        this.updatedAt = reservation.updatedAt();
        this.lines = reservation.lines().stream()
                .map(line -> new ReservationLineEntity(line.sku(), line.quantity()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    void apply(Reservation reservation) {
        this.status = reservation.status();
        this.updatedAt = reservation.updatedAt();
    }

    Reservation toDomain() {
        return new Reservation(
                id.toString(),
                orderId,
                lines.stream().map(line -> new ReservationLine(line.getSku(), line.getQuantity())).toList(),
                status,
                createdAt,
                updatedAt);
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
