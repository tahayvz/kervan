package com.kervan.search.infrastructure.elasticsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * İndeksi, alan tipleri tanımlıyken oluşturur.
 *
 * <h2>Neden gerekli?</h2>
 * Elasticsearch var olmayan bir indekse yazıldığında onu <b>kendi tahminiyle</b>
 * oluşturur. Tahmini de şudur: her metin alanı {@code text} olur. Yani marka ve
 * kategori analiz edilir, kelimelere bölünür ve iki şey birden bozulur:
 *
 * <ul>
 *   <li>Facet sayımı çalışmaz — analiz edilmiş alanda toplama yapılamaz.</li>
 *   <li>Süzme yanlış çalışır — "New Balance" araması "New" içeren her şeyi getirir.</li>
 * </ul>
 *
 * <p>Bu, sınıftaki {@code @Field} tanımlarının kendiliğinden uygulanmadığı anlamına
 * gelir: onlar yalnızca indeksi <b>biz</b> oluşturursak kullanılır. İlk belge
 * yazılmadan önce oluşturmak bu yüzden şart.
 *
 * <p>İndeks varsa dokunulmaz. Elasticsearch mevcut bir indeksin alan tipini
 * değiştirmeye izin vermez; eşleme değişikliği yeni indeks açıp veriyi yeniden
 * yazmayı (reindex) gerektirir. Bu okuma modeli türev olduğu için o iş, akışı baştan
 * oynatmaktan ibarettir.
 */
@Component
public class ProductIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexInitializer.class);

    private final ElasticsearchOperations elasticsearch;

    ProductIndexInitializer(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    /**
     * Uygulama hazır olduğunda çalışır — bağlam kurulurken değil.
     *
     * <p>Elasticsearch erişilemezse servis yine de ayağa kalkar ve sağlık ucundan
     * bunu bildirir; açılışta ölmek, geçici bir kesintide bütün kopyaların
     * başlayamaması demek olurdu.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        IndexOperations index = elasticsearch.indexOps(ProductIndexDocument.class);
        if (index.exists()) {
            log.debug("Ürün indeksi zaten var");
            return;
        }
        index.createWithMapping();
        log.info("Ürün indeksi tanımlı alan tipleriyle oluşturuldu");
    }
}
