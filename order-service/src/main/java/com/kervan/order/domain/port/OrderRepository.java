package com.kervan.order.domain.port;

import com.kervan.order.domain.model.Order;

import java.util.Optional;

/**
 * Siparişlerin kalıcılık sözleşmesi.
 * <p>
 * Domain bu arayüzü tanır, uygulamasını tanımaz; JPA adaptörü
 * {@code infrastructure.persistence} altındadır.
 */
public interface OrderRepository {

    /** Kaydeder ve kimliği atanmış hâlini döner. */
    Order save(Order order);

    Optional<Order> findById(String id);
}
