package com.kervan.inventory.domain.port;

import java.time.Instant;

/**
 * Stok tarafının duyurduğu olaylar.
 *
 * <p><b>Neden bu kadar dar bir arayüz?</b> Uygulama katmanı olayın hangi biçimde
 * (Avro), hangi konuya ve hangi tabloya yazıldığını bilmemelidir. Burada yalnızca
 * "şu oldu" denir; nasıl duyurulduğu {@code infrastructure} altındaki uygulamasının
 * işidir.
 *
 * <p>Genel bir {@code publish(Object)} yerine niyet bildiren metotlar var: böylece
 * hangi olayların var olduğu tek bakışta görülür ve yanlış tipte bir nesne
 * gönderilmesi mümkün olmaz.
 */
public interface InventoryEventPublisher {

    void stockReserved(String orderId, String reservationId, Instant at);

    /**
     * @param reason insan için açıklama; saga bunun içeriğine göre dallanmaz, hangi
     *               sebeple olursa olsun sipariş iptal edilir
     */
    void stockReservationFailed(String orderId, String reason, Instant at);

    void stockReleased(String orderId, String reservationId, Instant at);
}
