package com.kervan.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Bir mal kabulü: "şu makbuzla, şu SKU'dan, şu kadar geldi".
 *
 * <p>Stoğun neden değiştiğinin kaydıdır. Mutlak atama (“stok artık 500”) seçilseydi
 * böyle bir kayıt olamazdı: geriye yalnızca son sayı kalır, ona nasıl gelindiği
 * kaybolurdu (ADR-0019).
 *
 * @param receiptId istemcinin verdiği kimlik. Tekrarı durduran şey budur; sunucu
 *     üretseydi her yeniden deneme yeni bir makbuz olur ve miktar iki kez eklenirdi.
 */
public record StockReceipt(String receiptId, String sku, int quantity, Instant receivedAt) {

    public StockReceipt {
        Objects.requireNonNull(receiptId, "receiptId null olamaz");
        Objects.requireNonNull(sku, "sku null olamaz");
        Objects.requireNonNull(receivedAt, "receivedAt null olamaz");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Miktar pozitif olmalı: " + quantity);
        }
    }
}
