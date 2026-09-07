package com.kervan.search.application;

import com.kervan.search.domain.model.CatalogChange;
import com.kervan.search.domain.port.ProductIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Katalog değişikliklerini arama indeksine yansıtır.
 *
 * <h2>Bu servis hiçbir şey yazmaz</h2>
 * CQRS'in okuma tarafıdır: veri katalogta üretilir, burada yalnızca aramaya uygun bir
 * kopyası tutulur. İndeks kaybolsa bile veri kaybolmaz — akış baştan oynatılarak
 * yeniden kurulabilir.
 *
 * <h2>Tekrar gelen olaylar</h2>
 * Teslimat en az bir kezdir. Burada ayrı bir idempotentlik düzeneği yok, çünkü
 * indeksin kendisinde var: kayıt kaynak belgenin sürümüyle yazılır ve daha küçük bir
 * sürüm kabul edilmez. Tekrar gelen olay yeniden yazılır ve sonuç değişmez; geç
 * kalmış bir olay ise sessizce atlanır.
 */
@Service
public class ProductProjection {

    private static final Logger log = LoggerFactory.getLogger(ProductProjection.class);

    private final ProductIndex index;

    public ProductProjection(ProductIndex index) {
        this.index = index;
    }

    public void apply(CatalogChange change) {
        switch (change) {
            case CatalogChange.Upserted upserted -> {
                boolean written = index.index(upserted.product());
                log.debug("Ürün indekslendi: id={} yazıldı={}", upserted.productId(), written);
            }
            case CatalogChange.Deleted deleted -> {
                index.delete(deleted.productId());
                log.debug("Ürün indeksten silindi: id={}", deleted.productId());
            }
        }
    }
}
