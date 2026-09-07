package com.kervan.search.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Arama isteği.
 *
 * <p>Boş bırakılan her alan "bu kritere göre süzme" demektir; hiçbiri verilmezse
 * sorgu bütün kataloğu sayfalar.
 *
 * @param text          serbest metin — ad, açıklama ve markada aranır
 * @param brands        seçilen markalar; birden fazlası VEYA ile birleşir
 * @param categoryPath  kategori ön eki ("elektronik" → altındaki her şey)
 * @param minPrice      dâhil
 * @param maxPrice      dâhil
 */
public record SearchQuery(
        String text,
        List<String> brands,
        String categoryPath,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int page,
        int size) {

    /** Sayfa boyutunun üst sınırı; istemcinin tek istekte indeksi boşaltmasını önler. */
    public static final int MAX_SIZE = 100;

    public SearchQuery {
        brands = brands == null ? List.of() : List.copyOf(brands);
        if (page < 0) {
            throw new IllegalArgumentException("page negatif olamaz: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size 1 ile %d arasında olmalı: %d".formatted(MAX_SIZE, size));
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice, maxPrice'tan büyük olamaz");
        }
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
