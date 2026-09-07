package com.kervan.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment")
class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-10T12:05:00Z");

    private Payment captured() {
        return Payment.captured("order-1", "c-1", Money.of(new BigDecimal("100.00"), "TRY"), NOW);
    }

    @Test
    @DisplayName("yeni ödeme tahsil edilmiş durumda başlar")
    void startsCaptured() {
        assertThat(captured().isCaptured()).isTrue();
    }

    @Test
    @DisplayName("iade durumu değiştirir ve zamanı günceller")
    void refundChangesStatus() {
        Payment refunded = captured().refunded(LATER);

        assertThat(refunded.isCaptured()).isFalse();
        assertThat(refunded.updatedAt()).isEqualTo(LATER);
        assertThat(refunded.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("aynı ödeme iki kez iade edilemez")
    void cannotRefundTwice() {
        // Telafi komutu en az bir kez gelir; ikinci kez işlenirse müşteriye ikinci
        // kez para gönderilir.
        Payment refunded = captured().refunded(LATER);

        assertThatThrownBy(() -> refunded.refunded(LATER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zaten iade edilmiş");
    }

    @Test
    @DisplayName("tutar sıfır ya da negatif olamaz")
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ZERO, "TRY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1.00"), "TRY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tutarın ölçeği para biriminin hane sayısına getirilir")
    void normalisesScaleToCurrency() {
        // TRY 2 hane, KWD 3 hane. 10.5 ile 10.50 aynı değerdir.
        assertThat(Money.of(new BigDecimal("10.5"), "TRY").amount())
                .isEqualTo(new BigDecimal("10.50"));
        assertThat(Money.of(new BigDecimal("10.5"), "KWD").amount())
                .isEqualTo(new BigDecimal("10.500"));
    }
}
