package com.kervan.payment.domain.port;

import java.time.Instant;

/**
 * Yayınlanmayı bekleyen olayların yazıldığı yer.
 *
 * <p>Kayıt, tahsilatla <b>aynı transaction'da</b> yazılır: ikisi ya birlikte olur ya
 * hiçbiri (ADR-0004). Bu serviste kayıtları Debezium taşır; "yayınlandı" işareti yoktur.
 */
public interface OutboxRepository {

    void save(String aggregateId, String eventType, byte[] payload, Instant occurredAt);
}
