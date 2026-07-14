package com.kervan.catalog.application.exception;

/**
 * İstenen ürün bulunamadığında fırlatılır. Web katmanı bunu HTTP 404'e çevirir
 * (bkz. GlobalExceptionHandler).
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String id) {
        super("Ürün bulunamadı: " + id);
    }
}
