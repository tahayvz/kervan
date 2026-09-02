package com.kervan.order.domain.model;

import java.util.Objects;

/**
 * Siparişteki tek bir satır: hangi üründen kaç adet, hangi birim fiyatla.
 * <p>
 * <b>Neden fiyat burada saklanıyor?</b> Ürünün güncel fiyatı katalogdadır ve değişir.
 * Sipariş, verildiği andaki fiyatı taşımak zorundadır; aksi hâlde altı ay sonra
 * bakılan bir sipariş, o gün ödenmemiş bir tutar gösterir.
 */
public record OrderLine(String productId, String sku, int quantity, Money unitPrice) {

    public OrderLine {
        Objects.requireNonNull(productId, "productId null olamaz");
        Objects.requireNonNull(sku, "sku null olamaz");
        Objects.requireNonNull(unitPrice, "unitPrice null olamaz");
        if (quantity < 1) {
            throw new IllegalArgumentException("Satır adedi en az 1 olmalı: " + quantity);
        }
    }

    /** Satır toplamı: birim fiyat × adet. */
    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }
}
