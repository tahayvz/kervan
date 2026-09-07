package com.kervan.order.infrastructure.saga;

import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.port.SagaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class SagaRepositoryAdapter implements SagaRepository {

    private final SpringDataSagaRepository repository;

    SagaRepositoryAdapter(SpringDataSagaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OrderSaga> lockByOrderId(String orderId) {
        return repository.lockByOrderId(orderId).map(OrderSagaEntity::toDomain);
    }

    /**
     * Var olan satır güncellenir, yoksa yenisi açılır. Anahtar sipariş kimliği olduğu
     * için "hangisi" sorusu yok: sipariş başına tek saga.
     */
    @Override
    public OrderSaga save(OrderSaga saga) {
        return repository.findById(saga.orderId())
                .map(existing -> {
                    existing.apply(saga);
                    return existing.toDomain();
                })
                .orElseGet(() -> repository.save(new OrderSagaEntity(saga)).toDomain());
    }
}
