package com.kervan.order.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void shouldNormaliseScaleToCurrencyFractionDigits() {
        assertThat(Money.of("10.5", "TRY").amount()).isEqualByComparingTo("10.50");
    }

    @Test
    void shouldTreatEqualAmountsWithDifferentScaleAsEqual() {
        assertThat(Money.of("10.5", "TRY")).isEqualTo(Money.of("10.50", "TRY"));
    }

    @Test
    void shouldRejectNegativeAmount() {
        assertThatThrownBy(() -> Money.of("-1.00", "TRY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void add_shouldSumSameCurrency() {
        assertThat(Money.of("10.00", "TRY").add(Money.of("5.50", "TRY")).amount())
                .isEqualByComparingTo("15.50");
    }

    @Test
    void add_shouldRejectDifferentCurrency() {
        assertThatThrownBy(() -> Money.of("10.00", "TRY").add(Money.of("5.00", "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Farklı para birimleri");
    }

    @Test
    void multiply_shouldScaleAmount() {
        assertThat(Money.of("10.00", "TRY").multiply(3).amount())
                .isEqualByComparingTo("30.00");
    }

    @Test
    void zero_shouldStartAtZero() {
        assertThat(Money.zero(java.util.Currency.getInstance("TRY")).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
