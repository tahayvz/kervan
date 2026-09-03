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
     * Gönderilmeyi bekleyen kayıtları kilitleyerek okur; çağıran transaction bitene
     * kadar başka bir kopya aynı satırları almaz.
     *
     * @param limit       tek turda taşınacak azami kayıt
     * @param maxAttempts bu sayıya ulaşmış kayıtlar kenara alınır ve dönülmez
     */
    List<OutboxMessage> lockDeliverable(int limit, int maxAttempts);

    /** Yalnızca okuma — izleme ve testler için; kilit almaz. */
    List<OutboxMessage> findUnpublished(int limit);

    void markPublished(String id, Instant publishedAt);

    /** Başarısız denemeyi sayaca işler; sınıra ulaşan kayıt bir daha denenmez. */
    void recordFailedAttempt(String id, Instant at, String error);
}
