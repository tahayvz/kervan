package com.kervan.search.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Aramada gösterilen ürün.
 *
 * <h2>Neden katalogtaki modelin kopyası değil?</h2>
 * Bu bir <b>okuma modelidir</b> (CQRS). Katalog belgesi yazma için biçimlenmiştir;
 * burada sorulan sorular farklı: "şu markanın, şu fiyat aralığındaki ürünleri
 * göster". Aynı veriyi iki farklı şekle sokmak tekrar değil, iki farklı işin
 * gereğidir.
 *
 * <p>Katalogun her alanı buraya taşınmaz — yalnızca aranan, süzülen ve sonuç
 * listesinde gösterilenler. Taşınmayan bir alan, sonradan gerekirse eklenir;
 * gereksiz alan taşımak indeksi büyütür ve her değişiklikte yeniden yazdırır.
 *
 * @param version kaynak belgenin sürümü. Elasticsearch'te <b>dış sürüm</b> olarak
 *                kullanılır: geç kalmış ya da tekrar gelen bir olay, daha yeni bir
 *                kaydın üzerine yazamaz.
 */
public record SearchableProduct(
        String id,
        String sku,
        String name,
        String description,
        String brand,
        String categoryPath,
        BigDecimal price,
        String currency,
        String status,
        Map<String, Object> attributes,
        Instant updatedAt,
        long version) {

    public SearchableProduct {
        Objects.requireNonNull(id, "id null olamaz");
        Objects.requireNonNull(sku, "sku null olamaz");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
