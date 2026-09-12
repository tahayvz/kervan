package com.kervan.inventory.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

interface SpringDataStockReceiptRepository extends JpaRepository<StockReceiptEntity, String> {

    /**
     * Makbuzu yazar; kimlik zaten varsa hiçbir şey yapmaz.
     *
     * <p><b>Neden {@code save()} değil?</b> JPA'nın {@code save()}'i var olan bir kimlik
     * için <b>günceller</b>. Tekrar gelen bir makbuzda bu sessizce doğru görünüp yanlış
     * davranırdı: yazma başarılı sayılır, uygulama "yeni makbuz" sanar ve miktarı ikinci
     * kez eklerdi.
     *
     * <p>Karar veritabanına bırakılıyor. {@code ON CONFLICT DO NOTHING} etkilenen satır
     * sayısını döndürür: 1 ise ilk kez yazıldı, 0 ise tekrar. Bu, "önce sorgula sonra
     * yaz" ikilisinin arasına başka bir isteğin giremeyeceği <b>tek</b> yoldur.
     *
     * @return yazılan satır sayısı: ilk kez için 1, tekrar için 0
     */
    @Modifying
    @Query(value = "INSERT INTO stock_receipts (receipt_id, sku, quantity, received_at) "
            + "VALUES (:receiptId, :sku, :quantity, :receivedAt) "
            + "ON CONFLICT (receipt_id) DO NOTHING",
            nativeQuery = true)
    int insertIfNew(@Param("receiptId") String receiptId,
                    @Param("sku") String sku,
                    @Param("quantity") int quantity,
                    @Param("receivedAt") Instant receivedAt);
}
