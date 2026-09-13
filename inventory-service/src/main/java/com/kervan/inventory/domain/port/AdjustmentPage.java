package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.util.List;

/**
 * Denetim izinin bir sayfası.
 *
 * @param items bu sayfadaki kayıtlar, en yeniden eskiye
 * @param nextCursor sonraki sayfayı istemek için kullanılacak işaret; son sayfada
 *     {@code null}. İçeriği <b>opak</b>: istemci onu yorumlamaz, aynen geri gönderir.
 *     Böylece sayfalama yönteminin nasıl çalıştığı bir gün değişirse istemci kırılmaz.
 */
public record AdjustmentPage(List<StockAdjustment> items, String nextCursor) {
}
