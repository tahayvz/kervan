package com.kervan.payment.domain.port;

import com.kervan.payment.domain.model.Payment;

import java.util.Optional;

public interface PaymentRepository {

    /**
     * Sipariş için var olan ödemeyi döner.
     *
     * <p>İdempotentliğin ilk adımı: aynı komut ikinci kez geldiğinde burada bulunur ve
     * tahsilat tekrarlanmaz. Yarış hâlinde ikinci adım devreye girer —
     * {@code order_id} üzerindeki benzersizlik kısıtı.
     */
    Optional<Payment> findByOrderId(String orderId);

    Payment save(Payment payment);
}
