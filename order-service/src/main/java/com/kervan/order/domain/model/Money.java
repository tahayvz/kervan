package com.kervan.order.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Para birimiyle birlikte tutar.
 * <p>
 * <b>Neden ayrı bir tip?</b> Çıplak {@code BigDecimal} taşımak, farklı para birimlerini
 * yanlışlıkla toplamayı mümkün kılar. Bu tip toplama sırasında birimleri karşılaştırır
 * ve uyuşmazlıkta hata verir; hata çalışma anında değil, ilk toplamada ortaya çıkar.
 * <p>
 * Ölçek para biriminin ondalık hane sayısına sabitlenir (TRY/USD için 2), böylece
 * {@code 10.5} ile {@code 10.50} aynı değer sayılır.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount null olamaz");
        Objects.requireNonNull(currency, "currency null olamaz");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Tutar negatif olamaz: " + amount);
        }
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money multiply(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Farklı para birimleri toplanamaz: " + currency + " + " + other.currency);
        }
    }
}
