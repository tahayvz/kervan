package com.kervan.inventory.domain.model;

/**
 * Düzeltmeyi isteyen kişi, kendi isteğini onaylamaya çalıştı.
 *
 * <p><b>Bu kural, ikinci onayın tamamıdır.</b> Eşik yalnızca hangi düzeltmelerin
 * onaya düşeceğini söyler; korumayı sağlayan şey isteyenin karar verememesidir.
 * Aynı kişi hem isteyip hem onaylayabilseydi, süreç bir adım daha uzar ama hiçbir
 * şeyi engellemezdi (ADR-0022).
 */
public class SelfApprovalException extends RuntimeException {

    public SelfApprovalException(String adjustmentId) {
        super("Düzeltmeyi isteyen kişi kendi isteğini onaylayamaz: adjustmentId=" + adjustmentId);
    }
}
