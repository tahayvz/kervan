package com.kervan.search.domain.port;

import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.domain.model.SearchResult;
import com.kervan.search.domain.model.SearchableProduct;

/**
 * Arama indeksi.
 *
 * <p>Uygulama katmanı Elasticsearch'ü bilmez: hangi arama motorunun kullanıldığı bir
 * altyapı ayrıntısıdır ve değişebilir. Burada yalnızca "şunu indeksle", "şunu sil",
 * "şuna göre ara" denir.
 */
public interface ProductIndex {

    /**
     * Ürünü indekse yazar; varsa üzerine yazar.
     *
     * <p><b>Eski sürüm sessizce yok sayılır.</b> Teslimat en az bir kezdir ve olaylar
     * tekrar gelebilir; daha yeni bir kaydın üzerine eski bir olayla yazmak, aramayı
     * geçmişe döndürürdü. Sürüm karşılaştırması indeksin kendi işidir.
     *
     * @return yazıldıysa true, daha yeni bir kayıt olduğu için atlandıysa false
     */
    boolean index(SearchableProduct product);

    /** Ürünü indeksten çıkarır. Zaten yoksa hata değildir. */
    void delete(String productId);

    SearchResult search(SearchQuery query);
}
