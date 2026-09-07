package com.kervan.order.infrastructure.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface SpringDataOutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    /**
     * Gönderilmeyi bekleyen kayıtları kilitleyerek okur.
     * <p>
     * <b>Neden kilit?</b> Servis birden fazla kopya (replica) hâlinde çalışır. Kilitsiz
     * okumada iki kopya aynı satırları görür ve her olayı Kafka'ya iki kez yazar.
     * {@code FOR UPDATE} satırları bu transaction'a ait kılar; {@code SKIP LOCKED} ise
     * diğer kopyanın beklemek yerine bir sonraki satırlara geçmesini sağlar. Böylece
     * kopyalar birbirini bloklamadan işi bölüşür.
     * <p>
     * {@code attempts < :maxAttempts} filtresi, kalıcı olarak gönderilemeyen bir
     * kaydın kuyruğun başını tıkamasını engeller.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT o FROM OutboxEntity o
            WHERE o.publishedAt IS NULL AND o.attempts < :maxAttempts
            ORDER BY o.occurredAt ASC
            """)
    List<OutboxEntity> lockDeliverable(@Param("maxAttempts") int maxAttempts,
                                       org.springframework.data.domain.Limit limit);

    /** Yalnızca okunur: yayınlanmamış kayıtlar (test ve izleme için). */
    List<OutboxEntity> findByPublishedAtIsNullOrderByOccurredAtAsc(
            org.springframework.data.domain.Limit limit);

    @Modifying
    @Query("UPDATE OutboxEntity o SET o.publishedAt = :publishedAt WHERE o.id = :id")
    void markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    /**
     * Başarısız denemeyi kaydeder. Sayaç arttığı için aynı kayıt sonsuza kadar
     * denenmez; sınıra ulaşınca {@link #lockDeliverable} onu artık görmez.
     */
    @Modifying
    @Query("""
            UPDATE OutboxEntity o
            SET o.attempts = o.attempts + 1, o.lastAttemptAt = :at, o.lastError = :error
            WHERE o.id = :id
            """)
    void recordFailedAttempt(@Param("id") UUID id,
                             @Param("at") Instant at,
                             @Param("error") String error);

    /**
     * Silinecek kayıtların kimliklerini seçer.
     *
     * <p><b>Neden önce SELECT, sonra DELETE?</b> Tek bir {@code DELETE ... WHERE
     * occurred_at < ?} ifadesine sınır konamaz. Tablo büyümüşse bu, milyonlarca satırı
     * tek transaction'da siler: tablo uzun süre kilitli kalır ve sipariş yazan istekler
     * bekler. Kimlikleri sınırlı sayıda seçip onları silmek, temizliği küçük parçalara
     * böler — bir turda bitmezse bir sonraki turda devam eder.
     *
     * <p>{@code publishedOnly} true iken yalnızca damgalanmış kayıtlar seçilir.
     */
    @Query("""
            SELECT o.id FROM OutboxEntity o
            WHERE o.occurredAt < :cutoff
              AND (:publishedOnly = false OR o.publishedAt IS NOT NULL)
            ORDER BY o.occurredAt ASC
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff,
                              @Param("publishedOnly") boolean publishedOnly,
                              org.springframework.data.domain.Limit limit);

    @Modifying
    @Query("DELETE FROM OutboxEntity o WHERE o.id IN :ids")
    int deleteByIds(@Param("ids") List<UUID> ids);
}
