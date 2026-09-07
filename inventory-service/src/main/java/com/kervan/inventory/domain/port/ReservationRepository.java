package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.Reservation;

import java.util.Optional;

public interface ReservationRepository {

    /**
     * Sipariş için var olan ayırmayı döner.
     *
     * <p>İdempotentliğin ilk adımı budur: aynı komut ikinci kez geldiğinde burada
     * bulunur ve iş tekrar yapılmaz. Yarış durumunda ikinci adım devreye girer —
     * {@code order_id} üzerindeki benzersizlik kısıtı veritabanında.
     */
    Optional<Reservation> findByOrderId(String orderId);

    Reservation save(Reservation reservation);
}
