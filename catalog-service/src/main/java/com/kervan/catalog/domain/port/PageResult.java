package com.kervan.catalog.domain.port;

import java.util.List;

/**
 * Sayfalı sonuç — domain tarafında framework'süz sayfalama modeli.
 * <p>
 * Neden Spring'in {@code Page} tipini domain'e sokmuyoruz? Domain, veri erişim
 * teknolojisinden habersiz kalmalı (hexagonal). Bu record, sayfalamayı Spring Data'ya
 * bağımlı olmadan ifade eder; çeviri adaptör katmanında yapılır.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / size);
    }
}
