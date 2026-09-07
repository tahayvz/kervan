package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockItem;

import java.util.Collection;
import java.util.List;

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

    void saveAll(Collection<StockItem> items);
}
