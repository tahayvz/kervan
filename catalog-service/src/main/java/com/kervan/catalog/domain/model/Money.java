package com.kervan.catalog.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Para değeri (value object). Tutar + para birimi bir arada, değiştirilemez (immutable).
 * <p>
 * Neden value object? "Fiyat" bir kimlik değil bir değerdir; iki 100 TL birbirine eşittir.
 * Doğrulama tek yerde toplanır: negatif tutar veya geçersiz para birimi domain'e giremez.
 * Domain katmanı framework'süzdür — burada hiçbir Spring/Mongo importu yoktur.
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount null olamaz");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount negatif olamaz: " + amount);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency boş olamaz");
        }
        // ISO 4217 doğrulaması: geçersiz para birimi (örn. "XXX123") reddedilir
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("geçersiz para birimi: " + currency);
        }
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }
}
