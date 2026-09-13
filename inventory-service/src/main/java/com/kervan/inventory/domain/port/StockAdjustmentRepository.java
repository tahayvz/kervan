package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.util.List;

/**
 * Stok düzeltmelerinin defteri — yani denetim izi.
 *
 * <p>Bu depo iki iş yapar: tekrarı durdurur (makbuzdaki desenle aynı) ve kaydı
 * <b>okunabilir</b> tutar. İkincisi süs değil: okunamayan bir denetim izi, denetim
 * izi değildir.
 */
public interface StockAdjustmentRepository {

    /**
     * Düzeltmeyi kaydeder; aynı kimlik zaten varsa <b>yazmaz</b>.
     *
     * @return ilk kez kaydedildiyse {@code true}, tekrar ise {@code false}
     */
    boolean saveIfNew(StockAdjustment adjustment);

    /** Bir SKU'nun düzeltmeleri, en yeniden eskiye. */
    List<StockAdjustment> findBySku(String sku, int limit);
}
