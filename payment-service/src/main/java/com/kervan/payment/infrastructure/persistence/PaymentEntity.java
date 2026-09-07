package com.kervan.payment.infrastructure.persistence;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.Payment;
import com.kervan.payment.domain.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
class PaymentEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Kimlik uygulamada üretiliyor; Spring Data'nın gereksiz SELECT'ini önler. */
    @Transient
    private boolean isNew = true;

    protected PaymentEntity() {
        // JPA için
    }

    PaymentEntity(UUID id, Payment payment) {
        this.id = id;
        this.orderId = payment.orderId();
        this.customerId = payment.customerId();
        this.amount = payment.amount().amount();
        this.currency = payment.amount().currencyCode();
        this.status = payment.status();
        this.createdAt = payment.createdAt();
        this.updatedAt = payment.updatedAt();
    }

    void apply(Payment payment) {
        this.status = payment.status();
        this.updatedAt = payment.updatedAt();
    }

    Payment toDomain() {
        return new Payment(id.toString(), orderId, customerId,
                Money.of(amount, currency), status, createdAt, updatedAt);
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
