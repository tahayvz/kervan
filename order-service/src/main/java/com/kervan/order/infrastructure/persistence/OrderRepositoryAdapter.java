package com.kervan.order.infrastructure.persistence;

import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.port.OrderRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link OrderRepository} portunun JPA uygulaması.
 * <p>
 * Kimlik uygulamada üretilir (veritabanı sırasıyla değil). Böylece sipariş, henüz
 * commit edilmeden önce kimliğini bilir; outbox kaydı aynı transaction içinde bu
 * kimliğe referans verebilir.
 */
@Component
class OrderRepositoryAdapter implements OrderRepository {

    private final SpringDataOrderRepository repository;

    OrderRepositoryAdapter(SpringDataOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public Order save(Order order) {
        UUID id = order.id() == null ? UUID.randomUUID() : UUID.fromString(order.id());
        OrderEntity saved = repository.save(OrderMapper.toEntity(order, id));
        return OrderMapper.toDomain(saved);
    }

    @Override
    public Optional<Order> findById(String id) {
        return parseId(id).flatMap(repository::findById).map(OrderMapper::toDomain);
    }

    /** Geçersiz biçimli kimlik, hata değil "bulunamadı" demektir. */
    private Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
