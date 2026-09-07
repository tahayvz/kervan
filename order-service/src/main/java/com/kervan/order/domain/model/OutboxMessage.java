package com.kervan.order.domain.model;

import java.time.Instant;
import java.util.Arrays;
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
 * <h2>Hedef neden kayıtta duruyor?</h2>
 * Bu servis yalnızca kendi olaylarını değil, saga'nın diğer servislere gönderdiği
 * <b>komutları</b> da outbox'a yazar. Hepsi tek bir konuya gitseydi komutlar yanlış
 * yere düşerdi. Hedef satırın kendisinde durur; Debezium yönlendirmeyi ona göre yapar.
 *
 * <h2>Payload neden bayt?</h2>
 * Olay Avro ile serileştirilir; sonuç metin değil ikili veridir. Baytların ilk beş
 * baytı şemanın Schema Registry'deki kimliğini taşır, bu yüzden olayı okuyan taraf
 * hangi şemayla yazıldığını mesajın kendisinden bulur (ADR-0008).
 *
 * <p>Karar kaydı: {@code docs/adr/0004-transactional-outbox-debezium.md}
 */
public record OutboxMessage(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String destination,
        byte[] payload,
        Instant occurredAt,
        Instant publishedAt) {

    public OutboxMessage {
        Objects.requireNonNull(aggregateType, "aggregateType null olamaz");
        Objects.requireNonNull(aggregateId, "aggregateId null olamaz");
        Objects.requireNonNull(eventType, "eventType null olamaz");
        Objects.requireNonNull(destination, "destination null olamaz");
        Objects.requireNonNull(payload, "payload null olamaz");
        Objects.requireNonNull(occurredAt, "occurredAt null olamaz");
        // Dizi paylaşılan bir referanstır; kopyalanmazsa çağıran taraf kaydı
        // oluşturduktan sonra içeriğini değiştirebilirdi.
        payload = payload.clone();
    }

    /** @return payload'ın kopyası; dönen diziyi değiştirmek kaydı etkilemez. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Henüz yayınlanmamış yeni bir kayıt. */
    public static OutboxMessage pending(String aggregateType, String aggregateId,
                                        String eventType, String destination,
                                        byte[] payload, Instant occurredAt) {
        return new OutboxMessage(null, aggregateType, aggregateId, eventType, destination,
                payload, occurredAt, null);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * Kayıt tipinin ürettiği {@code equals}/{@code hashCode} diziyi referansa göre
     * karşılaştırır; aynı içerikli iki kayıt farklı sayılırdı. Payload'ı içeriğine
     * göre karşılaştıracak şekilde elle yazıldı.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxMessage that
                && Objects.equals(id, that.id)
                && Objects.equals(aggregateType, that.aggregateType)
                && Objects.equals(aggregateId, that.aggregateId)
                && Objects.equals(eventType, that.eventType)
                && Objects.equals(destination, that.destination)
                && Arrays.equals(payload, that.payload)
                && Objects.equals(occurredAt, that.occurredAt)
                && Objects.equals(publishedAt, that.publishedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, aggregateType, aggregateId, eventType, destination,
                Arrays.hashCode(payload), occurredAt, publishedAt);
    }

    @Override
    public String toString() {
        // Payload ikili veridir; log'a basılırsa okunmaz bir yığın üretir.
        // Yerine boyutu yazılır: sorun ararken asıl işe yarayan bilgi odur.
        return "OutboxMessage[id=%s, aggregateType=%s, aggregateId=%s, eventType=%s, destination=%s, payloadBytes=%d, occurredAt=%s, publishedAt=%s]"
                .formatted(id, aggregateType, aggregateId, eventType, destination,
                        payload.length, occurredAt, publishedAt);
    }
}
