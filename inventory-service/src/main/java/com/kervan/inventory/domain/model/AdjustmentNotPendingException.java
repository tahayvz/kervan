package com.kervan.inventory.domain.model;

/**
 * Onay ya da ret, beklemede olmayan bir düzeltme için istendi.
 *
 * <p>İki durumda olur ve ikisi de hatadır: kayıt zaten karara bağlanmıştır (çift
 * onay girişimi), ya da eşiğin altında olduğu için hiç onay gerektirmemiştir.
 * Sessizce başarılı dönmek, onaylayan kişiye bir şey yaptığını sandırırdı.
 */
public class AdjustmentNotPendingException extends RuntimeException {

    public AdjustmentNotPendingException(String adjustmentId, AdjustmentStatus status) {
        super("Düzeltme onay beklemiyor: adjustmentId=%s durum=%s".formatted(adjustmentId, status));
    }
}
