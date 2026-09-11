package com.kervan.catalog.infrastructure.cache;

import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.port.PageResult;
import com.kervan.catalog.domain.port.ProductQuery;
import com.kervan.catalog.domain.port.ProductRepository;
import com.kervan.catalog.infrastructure.persistence.ProductRepositoryAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Ürün okumalarını Redis üzerinden önbellekler.
 *
 * <h2>Neden sarmalayıcı (decorator)?</h2>
 * Önbellek bir kalıcılık ayrıntısıdır; iş kuralları onun varlığını bilmemeli.
 * {@link ProductRepository} portunu uygulayıp gerçek uygulamayı sarmalayınca uygulama
 * katmanında tek satır değişmiyor — önbellek yarın kaldırılsa da öyle.
 *
 * <h2>Neden {@code @Cacheable} anotasyonu kullanılmadı?</h2>
 * Anotasyon, metodun <b>döndürdüğü</b> nesneyi saklar; burada o nesne domain
 * {@code Product}'tır ve JSON'dan geri kurulabilmesi için domain'e Jackson
 * anotasyonları ya da açık bir kurucu eklemek gerekirdi. Onun yerine önbellek kendi
 * temsilini ({@link CachedProduct}) saklıyor ve okuma açıkça yapılıyor. Yapılandırma
 * yine Spring'in {@code RedisCacheManager}'ı — değişen tek şey çağrının açık olması.
 *
 * <h2>Redis erişilemezse</h2>
 * Okuma ve yazma denemeleri sarmalanır: önbellek hatası isteği düşürmez, yalnızca
 * veritabanına gidilir. Önbellek bir hızlandırmadır; erişilemediğinde katalog
 * çalışmaya devam etmeli.
 */
@Component
@Primary
class CachingProductRepository implements ProductRepository {

    private static final Logger log = LoggerFactory.getLogger(CachingProductRepository.class);

    private final ProductRepositoryAdapter delegate;
    private final CacheManager cacheManager;

    CachingProductRepository(ProductRepositoryAdapter delegate, CacheManager cacheManager) {
        this.delegate = delegate;
        this.cacheManager = cacheManager;
    }

    @Override
    public Optional<Product> findById(String id) {
        CachedProduct cached = read(id);
        if (cached != null) {
            return Optional.of(cached.toDomain());
        }

        Optional<Product> product = delegate.findById(id);
        product.ifPresent(this::write);
        return product;
    }

    /**
     * Yazma sonrası kayıt <b>silinir</b>, güncellenmez.
     *
     * <p>Güncellemek daha hızlı görünür ama yanlış olabilir: kaydedilen nesne ile
     * veritabanının döndürdüğü nesne aynı olmayabilir (sürüm alanı, sunucu tarafı
     * varsayılanlar). Silmek, bir sonraki okumanın doğru veriyi getirmesini garanti
     * eder — önbellek doğruluk kaynağı değil, kopyasıdır.
     */
    @Override
    public Product save(Product product) {
        Product saved = delegate.save(product);
        evict(saved.getId());
        return saved;
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
        evict(id);
    }

    /**
     * Arama önbelleklenmez. Sorgu uzayı çok geniş (metin + süzgeç + sayfa
     * birleşimleri) ve isabet oranı düşük olurdu; ayrıca her yazmada hangi sorgu
     * sonuçlarının bayatladığını bilmek mümkün değil. Aramanın hızlı olması
     * gerekiyorsa yeri burası değil, arama servisi.
     */
    @Override
    public PageResult<Product> search(ProductQuery query) {
        return delegate.search(query);
    }

    @Override
    public boolean existsBySku(String sku) {
        return delegate.existsBySku(sku);
    }

    private CachedProduct read(String id) {
        try {
            Cache cache = cache();
            return cache == null ? null : cache.get(id, CachedProduct.class);
        } catch (RuntimeException e) {
            // Bozuk kayıt ya da erişilemeyen Redis. İstek düşmemeli.
            log.warn("Önbellek okunamadı, veritabanına gidiliyor: id={} sebep={}", id, e.getMessage());
            return null;
        }
    }

    private void write(Product product) {
        try {
            Cache cache = cache();
            if (cache != null) {
                cache.put(product.getId(), CachedProduct.from(product));
            }
        } catch (RuntimeException e) {
            log.warn("Önbelleğe yazılamadı: id={} sebep={}", product.getId(), e.getMessage());
        }
    }

    private void evict(String id) {
        try {
            Cache cache = cache();
            if (cache != null) {
                cache.evict(id);
            }
        } catch (RuntimeException e) {
            // Temizleme kaçtı: kayıt TTL dolana kadar bayat kalabilir. Bu yüzden
            // TTL var ve bu yüzden süresiz önbellek kullanılmıyor.
            log.warn("Önbellek temizlenemedi, kayıt TTL'e kadar bayat kalabilir: id={} sebep={}",
                    id, e.getMessage());
        }
    }

    private Cache cache() {
        return cacheManager.getCache(RedisCacheConfig.PRODUCTS_CACHE);
    }
}
