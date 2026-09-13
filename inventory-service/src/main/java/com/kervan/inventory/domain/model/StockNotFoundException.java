package com.kervan.inventory.domain.model;

/**
 * Stok kaydı hiç açılmamış bir SKU üzerinde işlem yapılmaya çalışıldı.
 *
 * <p>Mal kabulünde bu bir hata <b>değildir</b> — kabul, kaydı kendisi açar. Düzeltmede
 * ise hatadır: var olmayan bir sayı düzeltilemez. "Hiç kaydı yok" ile "kaydı var ama
 * sıfır" farklı şeylerdir ve ikincisini birincisinden üretmek, olmayan bir geçmişi
 * varmış gibi göstermek olurdu.
 */
public class StockNotFoundException extends RuntimeException {

    private final String sku;

    public StockNotFoundException(String sku) {
        super("Stok kaydı bulunamadı: sku=" + sku);
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
