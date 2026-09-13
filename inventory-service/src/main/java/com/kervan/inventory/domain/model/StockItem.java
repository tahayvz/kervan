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
     * Sayım düzeltmesi: satılabilir miktarı verilen kadar artırır ya da azaltır (ADR-0021).
     *
     * <p><b>{@code reserved}'a dokunmaz</b> ve bu bilinçli. Ayrılmış miktar saga'nın
     * sahipliğindedir: orada duran şey bir müşteriye söz verilmiştir. Sayım farkını
     * oradan düşmek, siparişi olan birinin malını sessizce almak olurdu. Rezerve mal
     * gerçekten kaybolduysa doğru cevap stoğu düzeltmek değil, o siparişi iptal etmektir.
     *
     * @throws IllegalArgumentException delta sıfırsa; ya da azaltma satılabiliri
     *     eksiye düşürecekse. İkincisi sessizce sıfıra çekilmez: "5 tane kırıldı"
     *     denildiğinde elde 3 varsa, gerçek dünyada bir şey daha yanlış demektir ve
     *     bunu yuvarlamak o hatayı gizler.
     */
    public StockItem adjust(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("Düzeltme miktarı sıfır olamaz");
        }
        int result = Math.addExact(available, delta);
        if (result < 0) {
            throw new IllegalArgumentException(
                    "Düzeltme satılabilir miktarı eksiye düşürürdü: sku=%s mevcut=%d duzeltme=%d"
                            .formatted(sku, available, delta));
        }
        return new StockItem(sku, result, reserved);
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
