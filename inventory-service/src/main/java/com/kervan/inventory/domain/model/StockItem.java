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
     * Mal kabulü: gelen miktarı satılabilire ekler (ADR-0019).
     *
     * <p><b>Neden toplamalı, neden atama değil?</b> "Stok artık 500 olsun" demek, aynı
     * anda gelen iki girişten birini sessizce kaybetmek olurdu — ikisi de mevcut değeri
     * okur, ikisi de kendi sonucunu yazar, biri buharlaşır. Ekleme böyle bir kayıp
     * üretmez: iki teslimat da sayılır.
     *
     * <p>{@code reserved} değişmez. Gelen mal kimseye tutulmuş değildir; yalnızca
     * satılabilir havuza girer.
     *
     * @throws ArithmeticException toplam {@code int} sınırını aşarsa. Sessizce negatife
     *     dönmesi, stok yaratmaktan daha kötü olurdu: bir sonraki ayırma "yetersiz stok"
     *     der ve sebebi hiçbir yerde görünmez.
     */
    public StockItem receive(int quantity) {
        requirePositive(quantity);
        return new StockItem(sku, Math.addExact(available, quantity), reserved);
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
