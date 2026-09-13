package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.domain.model.StockAdjustment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Düzeltme tablosunun eşlemesi.
 *
 * <p>Yazma bu sınıftan geçmez ({@code ON CONFLICT DO NOTHING} ile yapılır), ama
 * <b>okuma geçer</b>: denetim izini listeleyen sorgu buradan çalışır. Ayrıca
 * {@code ddl-auto: validate} yalnızca eşlenmiş varlıkları denetler; bu sınıf
 * olmasaydı göç dosyasıyla kodun beklediği sütunlar sessizce kayabilirdi.
 */
@Entity
@Table(name = "stock_adjustments")
class StockAdjustmentEntity {

    @Id
    @Column(name = "adjustment_id")
    private String adjustmentId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false)
    private String reason;

    @Column
    private String note;

    @Column(name = "adjusted_by", nullable = false)
    private String adjustedBy;

    @Column(name = "adjusted_at", nullable = false)
    private Instant adjustedAt;

    protected StockAdjustmentEntity() {
        // JPA için
    }

    String getAdjustmentId() {
        return adjustmentId;
    }

    Instant getAdjustedAt() {
        return adjustedAt;
    }

    StockAdjustment toDomain() {
        return new StockAdjustment(adjustmentId, sku, delta,
                AdjustmentReason.valueOf(reason), note, adjustedBy, adjustedAt);
    }
}
