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
 * <p>Büyük düzeltmelerin ayrıca bir <b>kararı</b> vardır (ADR-0022): istendi, sonra
 * başka biri onayladı ya da reddetti.
 *
 * @param adjustmentId istemcinin verdiği kimlik; tekrarı durduran şey budur
 * @param delta pozitif ya da negatif, <b>asla sıfır değil</b>
 * @param adjustedBy token'daki {@code sub}. Kullanıcı adı değil: kullanıcı adı
 *     değişebilir, kayıt kalıcıdır ve değişmeyen bir kimliğe bağlanmalıdır.
 * @param decidedBy onaylayan ya da reddeden kişi; beklemede ve anında uygulananlarda
 *     {@code null}
 */
public record StockAdjustment(String adjustmentId,
                              String sku,
                              int delta,
                              AdjustmentReason reason,
                              String note,
                              String adjustedBy,
                              Instant adjustedAt,
                              AdjustmentStatus status,
                              String decidedBy,
                              Instant decidedAt) {

    public StockAdjustment {
        Objects.requireNonNull(adjustmentId, "adjustmentId null olamaz");
        Objects.requireNonNull(sku, "sku null olamaz");
        Objects.requireNonNull(reason, "reason null olamaz");
        Objects.requireNonNull(adjustedBy, "adjustedBy null olamaz");
        Objects.requireNonNull(adjustedAt, "adjustedAt null olamaz");
        Objects.requireNonNull(status, "status null olamaz");
        if (delta == 0) {
            // Hiçbir şey değiştirmeyen bir düzeltme yalnızca denetim izini kirletir.
            throw new IllegalArgumentException("Düzeltme miktarı sıfır olamaz");
        }
        // Veritabanındaki kısıtın kod tarafındaki eşi. İkisi de duruyor çünkü kod
        // değişir, veri kalır: kısıt kuralın unutulamayacağı yer, bu kurucu ise
        // kuralın bozulduğu anı işaret eden yer.
        if (status.isDecided() != (decidedBy != null && decidedAt != null)) {
            throw new IllegalArgumentException(
                    "Karar verilmiş düzeltmenin karar vereni ve zamanı olmalı, "
                            + "verilmemişin olmamalı: durum=" + status);
        }
    }

    /** Eşiğin altında kalan, istendiği anda uygulanan düzeltme. */
    public static StockAdjustment applied(String adjustmentId, String sku, int delta,
                                          AdjustmentReason reason, String note,
                                          String adjustedBy, Instant at) {
        return new StockAdjustment(adjustmentId, sku, delta, reason, note, adjustedBy, at,
                AdjustmentStatus.APPLIED, null, null);
    }

    /** Eşiğin üstünde kalan, onay bekleyen düzeltme. Stok <b>değişmemiştir</b>. */
    public static StockAdjustment pending(String adjustmentId, String sku, int delta,
                                          AdjustmentReason reason, String note,
                                          String adjustedBy, Instant at) {
        return new StockAdjustment(adjustmentId, sku, delta, reason, note, adjustedBy, at,
                AdjustmentStatus.PENDING, null, null);
    }

    /**
     * Onaylanmış hâli.
     *
     * @throws SelfApprovalException karar veren, isteyenle aynı kişiyse
     * @throws AdjustmentNotPendingException kayıt onay beklemiyorsa
     */
    public StockAdjustment approvedBy(String approver, Instant at) {
        requireDecidable(approver);
        return new StockAdjustment(adjustmentId, sku, delta, reason, note, adjustedBy, adjustedAt,
                AdjustmentStatus.APPROVED, approver, at);
    }

    /** Reddedilmiş hâli. Aynı kurallar geçerli: reddetmek de bir karardır. */
    public StockAdjustment rejectedBy(String rejecter, Instant at) {
        requireDecidable(rejecter);
        return new StockAdjustment(adjustmentId, sku, delta, reason, note, adjustedBy, adjustedAt,
                AdjustmentStatus.REJECTED, rejecter, at);
    }

    private void requireDecidable(String decider) {
        Objects.requireNonNull(decider, "karar veren null olamaz");
        if (status != AdjustmentStatus.PENDING) {
            throw new AdjustmentNotPendingException(adjustmentId, status);
        }
        // Ikinci onayin TAMAMI bu satir. Esik yalnizca hangi duzeltmelerin onaya
        // dusecegini soyler; korumayi saglayan sey isteyenin karar verememesidir.
        if (decider.equals(adjustedBy)) {
            throw new SelfApprovalException(adjustmentId);
        }
    }
}
