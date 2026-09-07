package com.kervan.inventory.domain.port;

import java.time.Instant;

/**
 * Yayınlanmayı bekleyen olayların yazıldığı yer.
 *
 * <p>Kayıt, stok değişikliğiyle <b>aynı transaction'da</b> yazılır: ikisi ya birlikte
 * olur ya hiçbiri. Olayı doğrudan Kafka'ya göndermek bu garantiyi veremezdi
 * (ADR-0004).
 *
 * <p>Bu serviste kayıtları Debezium taşır; bu yüzden "yayınlandı" işareti yoktur.
 */
public interface OutboxRepository {

    void save(String aggregateId, String eventType, byte[] payload, Instant occurredAt);
}
