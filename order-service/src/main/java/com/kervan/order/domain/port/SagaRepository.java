package com.kervan.order.domain.port;

import com.kervan.order.domain.model.OrderSaga;

import java.util.Optional;

public interface SagaRepository {

    /**
     * Saga'yı <b>kilitleyerek</b> okur.
     *
     * <p>Aynı siparişin iki olayı yakın zamanda gelebilir (örneğin bir tüketici
     * yeniden dengelenirken). Kilitsiz okumada ikisi de aynı durumu görür ve saga iki
     * kez ilerletilebilir.
     */
    Optional<OrderSaga> lockByOrderId(String orderId);

    OrderSaga save(OrderSaga saga);
}
