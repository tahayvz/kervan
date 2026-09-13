package com.kervan.inventory.domain.model;

/**
 * Bir stok düzeltmesinin durumu (ADR-0022).
 *
 * <p>Düzeltmelerin çoğunun yaşam döngüsü yoktur: küçük bir sayım farkı istendiği anda
 * uygulanır ve {@link #APPLIED} olarak kalır. Yaşam döngüsü yalnızca <b>büyük</b>
 * düzeltmelerde başlar, çünkü ikinci bir onay orada gerekir.
 */
public enum AdjustmentStatus {

    /** Eşiğin altında; istendiği anda uygulandı. Düzeltmelerin çoğu budur. */
    APPLIED,

    /** Eşiğin üstünde; <b>stok değişmedi</b>, ikinci bir onay bekliyor. */
    PENDING,

    /** Onaylandı ve stok o anda değişti. */
    APPROVED,

    /** Reddedildi; stok hiç değişmedi. */
    REJECTED;

    /** Stok bu durumda gerçekten değişmiş midir? */
    public boolean movedStock() {
        return this == APPLIED || this == APPROVED;
    }

    /** Karar verilmiş mi? Kararlının karar vereni ve zamanı olmak zorunda. */
    public boolean isDecided() {
        return this == APPROVED || this == REJECTED;
    }
}
