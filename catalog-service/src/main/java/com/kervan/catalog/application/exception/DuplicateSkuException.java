package com.kervan.catalog.application.exception;

/**
 * Aynı SKU ile ikinci bir ürün yaratılmaya çalışıldığında fırlatılır.
 * Web katmanı bunu HTTP 409 (Conflict)'e çevirir.
 * <p>
 * İki savunma hattı vardır: (1) burada uygulama-seviyesi kontrol (existsBySku),
 * (2) MongoDB'deki benzersiz indeks (uk_products_sku) — yarış durumunda bile
 * veritabanı son sözü söyler.
 */
public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("Bu SKU zaten mevcut: " + sku);
    }
}
