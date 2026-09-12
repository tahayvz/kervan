package com.kervan.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Makbuz tablosunun eşlemesi.
 *
 * <p>Yazma bu sınıftan geçmez; ekleme {@code ON CONFLICT DO NOTHING} ile yapılır
 * (bkz. {@link SpringDataStockReceiptRepository#insertIfNew}). Sınıf yine de duruyor:
 * {@code ddl-auto: validate} yalnızca <b>eşlenmiş</b> varlıkları denetler. Bu sınıf
 * olmasaydı göç dosyası ile kodun beklediği sütunlar birbirinden kaydığında hiçbir
 * yerde hata çıkmaz, sorun ilk yazmada çalışma anında görülürdü.
 */
@Entity
@Table(name = "stock_receipts")
class StockReceiptEntity {

    @Id
    @Column(name = "receipt_id")
    private String receiptId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected StockReceiptEntity() {
        // JPA için
    }
}
