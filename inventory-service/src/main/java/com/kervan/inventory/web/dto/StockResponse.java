package com.kervan.inventory.web.dto;

import com.kervan.inventory.domain.model.StockItem;

/**
 * Stok durumu.
 *
 * @param available satılabilir miktar
 * @param reserved bir siparişe tutulmuş ama henüz çıkmamış miktar
 * @param total ikisinin toplamı. Hesaplanabilir olduğu hâlde yazılıyor: ayırma sırasında
 *     değişmeyen tek sayı budur ve istemcinin onu yanlış hesaplama ihtimali var.
 */
public record StockResponse(String sku, int available, int reserved, int total) {

    public static StockResponse from(StockItem item) {
        return new StockResponse(item.sku(), item.available(), item.reserved(),
                item.available() + item.reserved());
    }
}
