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

    /**
     * Kimliği olmayan sipariş yeni kayıttır; olan ise durum değişikliğidir.
     *
     * <p>Bu ayrım şart. {@code OrderMapper.toEntity} her seferinde <b>yeni</b> bir
     * varlık üretir ve o varlık kendini "yeni" ilan eder; var olan bir siparişi
     * onunla kaydetmek INSERT denemesi olur ve birincil anahtar çakışır. Saga sipariş
     * durumunu güncellemeye başlayana kadar bu yol hiç kullanılmamıştı.
     */
    @Override
    public Order save(Order order) {
        if (order.id() == null) {
            OrderEntity saved = repository.save(OrderMapper.toEntity(order, UUID.randomUUID()));
            return OrderMapper.toDomain(saved);
        }

        OrderEntity existing = repository.findById(UUID.fromString(order.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Güncellenecek sipariş bulunamadı: " + order.id()));
        existing.applyStatus(order.status(), order.updatedAt());
        return OrderMapper.toDomain(existing);
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
