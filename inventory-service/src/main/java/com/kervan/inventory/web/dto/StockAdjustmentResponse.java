package com.kervan.inventory.web.dto;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.time.Instant;

/** Denetim izindeki tek bir kayıt. */
public record StockAdjustmentResponse(String adjustmentId,
                                      int delta,
                                      String reason,
                                      String note,
                                      String adjustedBy,
                                      Instant adjustedAt,
                                      String status,
                                      String decidedBy,
                                      Instant decidedAt) {

    public static StockAdjustmentResponse from(StockAdjustment a) {
        return new StockAdjustmentResponse(a.adjustmentId(), a.delta(), a.reason().name(),
                a.note(), a.adjustedBy(), a.adjustedAt(),
                a.status().name(), a.decidedBy(), a.decidedAt());
    }
}
