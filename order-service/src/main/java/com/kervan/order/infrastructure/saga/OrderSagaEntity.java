package com.kervan.order.infrastructure.saga;

import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.model.SagaState;
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

import java.time.Instant;

@Entity
@Table(name = "order_sagas")
class OrderSagaEntity implements Persistable<String> {

    @Id
    @Column(name = "order_id")
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    @Column(name = "reservation_id")
    private String reservationId;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Anahtar uygulamadan gelir; Spring Data'nın INSERT öncesi SELECT'ini önler. */
    @Transient
    private boolean isNew = true;

    protected OrderSagaEntity() {
        // JPA için
    }

    OrderSagaEntity(OrderSaga saga) {
        this.orderId = saga.orderId();
        apply(saga);
    }

    final void apply(OrderSaga saga) {
        this.state = saga.state();
        this.reservationId = saga.reservationId();
        this.paymentId = saga.paymentId();
        this.createdAt = saga.createdAt();
        this.updatedAt = saga.updatedAt();
    }

    OrderSaga toDomain() {
        return new OrderSaga(orderId, state, reservationId, paymentId, createdAt, updatedAt);
    }

    @Override
    public String getId() {
        return orderId;
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
