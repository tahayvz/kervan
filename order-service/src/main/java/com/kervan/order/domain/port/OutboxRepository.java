package com.kervan.order.domain.port;

import com.kervan.order.domain.model.OutboxMessage;

import java.time.Instant;
import java.util.List;

/**
 * Outbox kayıtlarının kalıcılık sözleşmesi.
 * <p>
 * {@link #save} çağrısı, siparişi kaydeden transaction'ın <b>içinde</b> yapılmalıdır;
 * outbox'ın tüm değeri bu atomikliktir.
 */
public interface OutboxRepository {

    OutboxMessage save(OutboxMessage message);

    /**
     * Henüz yayınlanmamış kayıtları en eskiden yeniye döner.
     *
     * @param limit tek turda taşınacak azami kayıt; yayıncının bir seferde tüm tabloyu
     *              belleğe almasını engeller
     */
    List<OutboxMessage> findUnpublished(int limit);

    void markPublished(String id, Instant publishedAt);
}
