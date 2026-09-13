package com.kervan.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Bir stok düzeltmesi: "şu kişi, şu tarihte, şu SKU'yu şu kadar, şu gerekçeyle değiştirdi".
 *
 * <p>Mal kabulünden ({@link StockReceipt}) farkı niyetinde: makbuz bir <b>olay</b>
 * kaydıdır (dışarıda bir şey oldu), düzeltme bir <b>iddiadır</b> (bizim sayımız
 * yanlıştı). İddia olanın sahibi ve gerekçesi olmak zorundadır (ADR-0021).
 *
 * @param adjustmentId istemcinin verdiği kimlik; tekrarı durduran şey budur
 * @param delta pozitif ya da negatif, <b>asla sıfır değil</b>
 * @param adjustedBy token'daki {@code sub}. Kullanıcı adı değil: kullanıcı adı
 *     değişebilir, kayıt kalıcıdır ve değişmeyen bir kimliğe bağlanmalıdır.
 */
public record StockAdjustment(String adjustmentId,
                              String sku,
                              int delta,
                              AdjustmentReason reason,
                              String note,
                              String adjustedBy,
                              Instant adjustedAt) {

    public StockAdjustment {
        Objects.requireNonNull(adjustmentId, "adjustmentId null olamaz");
        Objects.requireNonNull(sku, "sku null olamaz");
        Objects.requireNonNull(reason, "reason null olamaz");
        Objects.requireNonNull(adjustedBy, "adjustedBy null olamaz");
        Objects.requireNonNull(adjustedAt, "adjustedAt null olamaz");
        if (delta == 0) {
            // Hiçbir şey değiştirmeyen bir düzeltme yalnızca denetim izini kirletir.
            throw new IllegalArgumentException("Düzeltme miktarı sıfır olamaz");
        }
    }
}
