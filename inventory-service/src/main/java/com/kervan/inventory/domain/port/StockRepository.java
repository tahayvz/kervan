package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockItem;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository {

    /**
     * Verilen SKU'ları <b>kilitleyerek</b> okur; çağıran transaction bitene kadar
     * başka bir işlem bu satırları değiştiremez.
     *
     * <p><b>Neden kilit?</b> Stok düşme işlemi "oku, hesapla, yaz" adımlarından oluşur.
     * İki sipariş aynı anda son ürünü okursa ikisi de yeterli stok görür ve ikisi de
     * satar. Kilit, ikinciyi birincinin commit'ini beklemeye zorlar.
     *
     * <p><b>Neden sıralı?</b> Satırlar her zaman aynı sırada (SKU'ya göre artan)
     * kilitlenir. Aksi hâlde A ve B ürünlerini farklı sıralarla kilitleyen iki
     * transaction birbirini bekler ve <em>deadlock</em> oluşur. Sabit sıra bunu
     * yapısal olarak imkânsız kılar — yeniden deneme mantığı gerekmez.
     *
     * <p>Bulunamayan SKU'lar dönen listede yer almaz; çağıran eksiği kendisi anlar.
     */
    List<StockItem> lockAll(Collection<String> skus);

    /**
     * SKU için stok satırı yoksa sıfır miktarla açar; varsa <b>hiçbir şey yapmaz</b>.
     *
     * <p>Mal kabulü, daha önce hiç görülmemiş bir SKU için de gelebilir. Satırı
     * "önce bak, yoksa ekle" diye açmak yarışa açıktır: aynı yeni SKU için gelen iki
     * makbuz da satırı görmez, ikisi de eklemeye çalışır, biri birincil anahtara
     * takılır ve istek 500 döner. Burada ekleme veritabanına <em>koşullu</em>
     * yaptırılıyor, böylece ikinci çağrı sessizce ve doğru şekilde hiçbir şey yapmaz.
     *
     * <p>Çağrıldıktan sonra satırın var olduğu garantidir; devamında
     * {@link #lockAll(Collection)} ile kilitlenip normal yoldan güncellenir.
     */
    void createIfAbsent(String sku);

    /**
     * Stok durumunu <b>kilitlemeden</b> okur.
     *
     * <p>{@link #lockAll(Collection)} ile karıştırılmamalı: o, arkasından yazma gelecek
     * olan okumalar içindir ve satırı transaction bitene kadar tutar. Bir görüntüleme
     * isteğinin bunu yapması, aynı SKU'ya gelen siparişleri sebepsiz bekletirdi.
     */
    Optional<StockItem> find(String sku);

    /**
     * Var olan satırları günceller.
     *
     * <p><b>Yalnızca GÜNCELLER.</b> Adı "kaydet" olmasına rağmen olmayan bir satırı
     * açmaz; açmaya çalışırsa {@code IllegalStateException} atar. Yeni satır için
     * {@link #createIfAbsent(String)} kullanılır.
     *
     * <p>Bu ayrım bilinçli ve adı bilerek dar tutulmadı: bu depoda daha önce genel
     * isimli bir metodun (order-service'teki {@code save()}) sessizce dar davranması
     * saatler yemişti. Burada davranış belgede açıkça yazılı.
     */
    void saveAll(Collection<StockItem> items);
}
