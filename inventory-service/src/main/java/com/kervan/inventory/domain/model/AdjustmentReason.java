package com.kervan.inventory.domain.model;

/**
 * Stok düzeltmesinin gerekçesi (ADR-0021).
 *
 * <p><b>Neden serbest metin değil?</b> Serbest bırakılsaydı "kırık", "kirik",
 * "hasarlı", "damaged" hepsi ayrı değer olurdu ve "bu ay ne kadar mal kırıldı"
 * sorusu hiçbir zaman cevaplanamazdı. Denetim izinin değeri, okunabilmesinde;
 * okunabilmesi de sınırlı bir listede.
 *
 * <p>Liste kasten kısa. Her yeni değer, geçmiş kayıtları yeniden yorumlamayı
 * gerektirir; eklemek ucuz görünür ama geriye dönük olarak pahalıdır.
 */
public enum AdjustmentReason {

    /** Fiziksel sayım sistemdekinden farklı çıktı. En sık kullanılan. */
    COUNT_CORRECTION,

    /** Mal hasar gördü ve satılamaz durumda. */
    DAMAGED,

    /** Raf ömrü doldu. */
    EXPIRED,

    /** Kayıp ya da hırsızlık. */
    SHRINKAGE,

    /** Tedarikçiye iade edildi. */
    RETURNED_TO_SUPPLIER,

    /**
     * Yukarıdakilerin hiçbiri.
     *
     * <p>Bu seçildiğinde açıklama <b>zorunludur</b>: gerekçesiz bir "diğer",
     * gerekçe yazmamakla aynı şeydir. Kural {@code StockAdjustmentService}'te.
     */
    OTHER
}
