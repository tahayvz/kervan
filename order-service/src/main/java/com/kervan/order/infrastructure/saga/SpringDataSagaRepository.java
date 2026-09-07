package com.kervan.order.infrastructure.saga;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataSagaRepository extends JpaRepository<OrderSagaEntity, String> {

    /**
     * Saga satırını kilitleyerek okur.
     *
     * <p>Aynı siparişin iki olayı yakın zamanda gelebilir — örneğin tüketici yeniden
     * dengelenirken bir mesaj tekrar teslim edilir. Kilitsiz okumada iki işleme de
     * aynı durumu görür ve saga iki kez ilerletilebilirdi: aynı komut iki kez
     * gönderilir, ikinci kez ödeme istenir.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM OrderSagaEntity s WHERE s.orderId = :orderId")
    Optional<OrderSagaEntity> lockByOrderId(@Param("orderId") String orderId);
}
