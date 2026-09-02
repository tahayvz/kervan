package com.kervan.order.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Yayınlanmayı bekleyen bir olayın veritabanındaki kaydı.
 *
 * <h2>Neden doğrudan Kafka'ya yazmıyoruz?</h2>
 * Sipariş kaydetmek ve olayı yayınlamak iki ayrı sistemdir; ortak bir transaction'ları
 * yoktur. Doğrudan yayınlama denendiğinde iki hata mümkündür:
 * <ul>
 *   <li>Veritabanı commit edildi, Kafka'ya yazmadan önce servis çöktü → sipariş var,
 *       olay yok. Stok düşülmez, bildirim gitmez; kimse fark etmez.</li>
 *   <li>Kafka'ya yazıldı, veritabanı rollback oldu → olay var, sipariş yok. Downstream
 *       servisler var olmayan bir siparişi işler.</li>
 * </ul>
 * Buna <em>dual-write problemi</em> denir ve retry ile çözülmez; hangi tarafın doğru
 * olduğu bilinemez.
 *
 * <h2>Outbox nasıl çözüyor?</h2>
 * Olay, siparişle <b>aynı transaction içinde</b> aynı veritabanına yazılır. Tek bir
 * commit olduğu için ikisi ya birlikte var olur ya birlikte yok olur. Ayrı bir süreç
 * ({@code OutboxPublisher}) yazılmış kayıtları okuyup Kafka'ya taşır ve başarılı
 * olanları işaretler.
 *
 * <p>Bunun bedeli: teslimat <b>en az bir kez</b> (at-least-once) olur. Yayınlandıktan
 * sonra işaretlemeden önce çökme olursa aynı olay tekrar gider. Bu yüzden tüketiciler
 * idempotent olmak zorundadır; {@link #id} tekrarları elemek için sabit bir anahtardır.
 *
 * <p>Karar kaydı: {@code docs/adr/0004-transactional-outbox-debezium.md}
 */
public record OutboxMessage(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        Instant publishedAt) {

    public OutboxMessage {
        Objects.requireNonNull(aggregateType, "aggregateType null olamaz");
        Objects.requireNonNull(aggregateId, "aggregateId null olamaz");
        Objects.requireNonNull(eventType, "eventType null olamaz");
        Objects.requireNonNull(payload, "payload null olamaz");
        Objects.requireNonNull(occurredAt, "occurredAt null olamaz");
    }

    /** Henüz yayınlanmamış yeni bir kayıt. */
    public static OutboxMessage pending(String aggregateType, String aggregateId,
                                        String eventType, String payload, Instant occurredAt) {
        return new OutboxMessage(null, aggregateType, aggregateId, eventType,
                payload, occurredAt, null);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
