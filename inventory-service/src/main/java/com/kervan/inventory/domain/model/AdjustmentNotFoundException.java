package com.kervan.inventory.domain.model;

/** Onay ya da ret, hiç var olmayan bir düzeltme kimliği için istendi. */
public class AdjustmentNotFoundException extends RuntimeException {

    public AdjustmentNotFoundException(String adjustmentId) {
        super("Düzeltme bulunamadı: adjustmentId=" + adjustmentId);
    }
}
