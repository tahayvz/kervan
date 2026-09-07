package com.kervan.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Para birimiyle birlikte tutar.
 *
 * <p><b>order-service'te de aynı adla bir tip var; neden paylaşılmıyor?</b> İkisi ayrı
 * bounded context. Ortak bir kütüphaneye taşımak, iki servisi birbirine bağlar: para
 * kuralı birinin ihtiyacıyla değiştiğinde diğeri de değişmek zorunda kalır. Mikroservis
 * sınırında küçük bir tekrar, paylaşılan bir bağımlılıktan ucuzdur.
 *
 * <p>Ölçek para biriminin ondalık hane sayısına sabitlenir; {@code 10.5} ile
 * {@code 10.50} aynı değer sayılır.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount null olamaz");
        Objects.requireNonNull(currency, "currency null olamaz");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Tahsil edilecek tutar pozitif olmalı: " + amount);
        }
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.getInstance(currencyCode));
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }
}
