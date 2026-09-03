package com.kervan.order.infrastructure.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import com.kervan.order.domain.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code orders} tablosunun JPA karşılığı.
 * <p>
 * Domain'deki {@code Order} sınıfından ayrıdır: bu sınıf tabloyu, o sınıf iş kurallarını
 * temsil eder. Dönüşüm {@link OrderMapper} içindedir.
 */
@Entity
@Table(name = "orders")
class OrderEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderLineEntity> lines = new ArrayList<>();

    /**
     * Kimlik uygulamada üretiliyor. Bunu söylemezsek Spring Data kaydı "mevcut" sayar,
     * her INSERT'ten önce kesin ıskalayacak bir SELECT çalıştırır ve sipariş oluşturma
     * yolunu gereksiz yere iki katına çıkarır.
     */
    @Transient
    private boolean isNew = true;

    protected OrderEntity() {
        // JPA için
    }

    OrderEntity(UUID id, String customerId, BigDecimal totalAmount, String currency,
                OrderStatus status, Instant placedAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = status;
        this.placedAt = placedAt;
        this.updatedAt = updatedAt;
    }

    void addLine(OrderLineEntity line) {
        lines.add(line);
        line.setOrder(this);
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

    String getCustomerId() {
        return customerId;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    String getCurrency() {
        return currency;
    }

    OrderStatus getStatus() {
        return status;
    }

    Instant getPlacedAt() {
        return placedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    List<OrderLineEntity> getLines() {
        return lines;
    }
}
