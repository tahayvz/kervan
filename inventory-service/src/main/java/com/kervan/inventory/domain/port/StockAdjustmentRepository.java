package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * Bir SKU'nun düzeltmeleri, en yeniden eskiye.
     *
     * <p><b>Anahtar tabanlı (keyset) sayfalama, OFFSET değil.</b> Denetim izi ekleme
     * yapılan bir defterdir: {@code OFFSET} ile sayfa çevirirken araya yeni bir kayıt
     * girerse sayfa sınırı kayar ve okuyan kişi bir kaydı <em>iki kez görür</em> ya da
     * <em>hiç görmez</em>. İkincisi bir denetim izinde kabul edilemez.
     *
     * <p>Sıralama {@code (adjusted_at DESC, adjustment_id DESC)}. İkinci alan bir
     * süs değil: aynı milisaniyede yazılmış iki kayıt varsa sıra belirsiz kalır ve
     * sayfalama o noktada bir kaydı atlar.
     *
     * @param beforeAt bu andan ÖNCEKİLER; ilk sayfa için {@code null}
     * @param beforeId aynı anı paylaşan kayıtlar için ikinci sınır; ilk sayfa için
     *     {@code null}
     */
    List<StockAdjustment> findBySku(String sku, Instant beforeAt, String beforeId, int limit);

    /** Tek bir düzeltmeyi kimliğiyle okur. Onay ve ret buradan başlar. */
    Optional<StockAdjustment> find(String adjustmentId);

    /**
     * Kararı yazar (onay ya da ret).
     *
     * <p>Yalnızca <b>beklemede</b> olan bir kaydı günceller ve kaç satır etkilendiğini
     * döndürür. Sıfır dönmesi, araya başka birinin girip aynı kaydı karara bağladığı
     * anlamına gelir — "önce oku, sonra yaz" arasındaki boşluğu kapatan şey bu.
     *
     * @return güncellenen satır sayısı: karar bu çağrıyla verildiyse 1, başkası
     *     önce davrandıysa 0
     */
    int decideIfPending(StockAdjustment decided);
}
