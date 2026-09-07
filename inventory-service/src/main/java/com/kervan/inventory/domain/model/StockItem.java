package com.kervan.inventory.domain.model;

import java.util.Objects;

/**
 * Bir ürünün stok durumu.
 *
 * <h2>Neden iki sayı?</h2>
 * {@code available} satılabilir miktardır, {@code reserved} ise bir siparişe
 * tutulmuş ama henüz çıkmamış miktardır. Tek sayı tutulsaydı, ödeme başarısız
 * olduğunda ne kadarını geri vereceğimizi bilemezdik — ayırma geri alınabilir bir
 * işlemdir ve geri almak için neyin tutulduğunu bilmek gerekir.
 *
 * <p>Toplam ({@code available + reserved}) ayırma ve geri bırakma sırasında değişmez;
 * yalnızca iki kova arasında yer değiştirir.
 */
public record StockItem(String sku, int available, int reserved) {

    public StockItem {
        Objects.requireNonNull(sku, "sku null olamaz");
        if (available < 0) {
            throw new IllegalArgumentException("available negatif olamaz: " + available);
        }
        if (reserved < 0) {
            throw new IllegalArgumentException("reserved negatif olamaz: " + reserved);
        }
    }

    /**
     * Verilen miktarı satılabilirden ayrılmışa taşır.
     *
     * @throws InsufficientStockException yeterli satılabilir miktar yoksa
     */
    public StockItem reserve(int quantity) {
        requirePositive(quantity);
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        return new StockItem(sku, available - quantity, reserved + quantity);
    }

    /**
     * Ayrılmış miktarı satılabilire geri taşır (Saga telafisi).
     *
     * <p>Tutulandan fazlasını geri bırakmak stok yaratmak olurdu; bu bir hesap
     * hatasıdır ve sessizce düzeltilmez.
     */
    public StockItem release(int quantity) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalArgumentException(
                    "Tutulandan fazlası geri bırakılamaz: sku=%s istenen=%d tutulan=%d"
                            .formatted(sku, quantity, reserved));
        }
        return new StockItem(sku, available + quantity, reserved - quantity);
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Miktar pozitif olmalı: " + quantity);
        }
    }
}
