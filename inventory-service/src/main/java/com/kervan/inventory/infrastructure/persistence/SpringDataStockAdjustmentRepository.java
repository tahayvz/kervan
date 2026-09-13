package com.kervan.inventory.infrastructure.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface SpringDataStockAdjustmentRepository extends JpaRepository<StockAdjustmentEntity, String> {

    /**
     * Düzeltmeyi yazar; kimlik zaten varsa hiçbir şey yapmaz.
     *
     * <p>Makbuzdaki ile aynı gerekçe: JPA'nın {@code save()}'i var olan bir kimlik için
     * <b>günceller</b>, yani tekrar gelen bir istek "yeni kayıt" sanılır ve düzeltme
     * ikinci kez uygulanırdı. Karar veritabanına bırakılıyor; dönen satır sayısı
     * "yeni mi, tekrar mı" sorusunu tek adımda cevaplıyor.
     */
    @Modifying
    @Query(value = "INSERT INTO stock_adjustments "
            + "(adjustment_id, sku, delta, reason, note, adjusted_by, adjusted_at, status) "
            + "VALUES (:adjustmentId, :sku, :delta, :reason, :note, :adjustedBy, :adjustedAt, :status) "
            + "ON CONFLICT (adjustment_id) DO NOTHING",
            nativeQuery = true)
    int insertIfNew(@Param("adjustmentId") String adjustmentId,
                    @Param("sku") String sku,
                    @Param("delta") int delta,
                    @Param("reason") String reason,
                    @Param("note") String note,
                    @Param("adjustedBy") String adjustedBy,
                    @Param("adjustedAt") Instant adjustedAt,
                    @Param("status") String status);

    /**
     * Kararı yazar; kayıt <b>beklemede değilse</b> hiçbir şey yapmaz.
     *
     * <p>{@code WHERE ... AND status = 'PENDING'} bir süs değil. "Önce oku, beklemede
     * mi diye bak, sonra yaz" üç adımdır ve aralarına ikinci bir onaylayan girebilir;
     * ikisi de beklemede görür, ikisi de yazar ve stok İKİ KEZ değişir. Koşulu
     * yazmanın kendisine koymak o pencereyi kapatır: ikinciye sıfır satır döner.
     *
     * <p>Aynı desen bu serviste zaten iki kez kullanıldı — makbuz ve düzeltme
     * eklemede {@code ON CONFLICT DO NOTHING}. Karar tek bir ifadeye bırakılıyor.
     */
    @Modifying
    @Query(value = "UPDATE stock_adjustments "
            + "SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt "
            + "WHERE adjustment_id = :adjustmentId AND status = 'PENDING'",
            nativeQuery = true)
    int decideIfPending(@Param("adjustmentId") String adjustmentId,
                        @Param("status") String status,
                        @Param("decidedBy") String decidedBy,
                        @Param("decidedAt") Instant decidedAt);

    /** Denetim izinin ilk sayfası: en yeni kayıtlar. */
    List<StockAdjustmentEntity> findBySkuOrderByAdjustedAtDescAdjustmentIdDesc(String sku, Limit limit);

    /**
     * Sonraki sayfa: verilen noktadan ÖNCEKİLER.
     *
     * <p>Karşılaştırma iki alan üzerinden:
     * {@code adjustedAt < :beforeAt} <b>ya da</b>
     * {@code (adjustedAt = :beforeAt ve adjustmentId < :beforeId)}.
     *
     * <p>İkinci koşul bir süs değil. Aynı milisaniyede yazılmış iki kayıt varsa,
     * sayfa sınırı tam onların ortasına düşebilir ve biri <b>hiç görünmez</b>. Bir
     * denetim izinde "bazen bir kayıt atlanıyor" kabul edilemez; izin değeri
     * tamlığındadır.
     */
    @Query("SELECT a FROM StockAdjustmentEntity a "
            + "WHERE a.sku = :sku "
            + "AND (a.adjustedAt < :beforeAt "
            + "     OR (a.adjustedAt = :beforeAt AND a.adjustmentId < :beforeId)) "
            + "ORDER BY a.adjustedAt DESC, a.adjustmentId DESC")
    List<StockAdjustmentEntity> findPageBefore(@Param("sku") String sku,
                                               @Param("beforeAt") Instant beforeAt,
                                               @Param("beforeId") String beforeId,
                                               Limit limit);
}
