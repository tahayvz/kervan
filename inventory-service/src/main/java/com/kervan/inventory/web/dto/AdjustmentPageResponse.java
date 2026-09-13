package com.kervan.inventory.web.dto;

import com.kervan.inventory.domain.port.AdjustmentPage;

import java.util.List;

/**
 * Denetim izinin bir sayfası.
 *
 * @param items kayıtlar, en yeniden eskiye
 * @param nextCursor sonraki sayfa için {@code ?cursor=} değeri. <b>Son sayfada
 *     {@code null}</b> — istemci "daha var mı" sorusunu tahmin etmek zorunda kalmaz.
 *     İçeriği opaktır: yorumlanmaz, aynen geri gönderilir.
 */
public record AdjustmentPageResponse(List<StockAdjustmentResponse> items, String nextCursor) {

    public static AdjustmentPageResponse from(AdjustmentPage page) {
        return new AdjustmentPageResponse(
                page.items().stream().map(StockAdjustmentResponse::from).toList(),
                page.nextCursor());
    }
}
