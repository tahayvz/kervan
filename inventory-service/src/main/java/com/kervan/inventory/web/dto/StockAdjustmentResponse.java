package com.kervan.inventory.web.dto;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.time.Instant;

/** Denetim izindeki tek bir kayıt. */
public record StockAdjustmentResponse(String adjustmentId,
                                      int delta,
                                      String reason,
                                      String note,
                                      String adjustedBy,
                                      Instant adjustedAt) {

    public static StockAdjustmentResponse from(StockAdjustment a) {
        return new StockAdjustmentResponse(a.adjustmentId(), a.delta(), a.reason().name(),
                a.note(), a.adjustedBy(), a.adjustedAt());
    }
}
