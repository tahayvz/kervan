package com.kervan.inventory.domain.model;

/**
 * Ayrılmak istenen miktar mevcut stoktan fazla.
 *
 * <p>Bu bir programlama hatası değil, işin normal bir sonucudur: stok biter. Saga
 * bunu bir başarısızlık adımı olarak ele alır ve siparişi iptal eder.
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Stok yetersiz: sku=%s istenen=%d mevcut=%d".formatted(sku, requested, available));
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
