package com.kervan.order.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderLineTest {

    @Test
    void lineTotal_shouldMultiplyUnitPriceByQuantity() {
        OrderLine line = new OrderLine("p-1", "SKU-1", 4, Money.of("12.50", "TRY"));

        assertThat(line.lineTotal().amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void shouldRejectZeroQuantity() {
        assertThatThrownBy(() -> new OrderLine("p-1", "SKU-1", 0, Money.of("1.00", "TRY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en az 1");
    }

    @Test
    void shouldRejectNegativeQuantity() {
        assertThatThrownBy(() -> new OrderLine("p-1", "SKU-1", -2, Money.of("1.00", "TRY")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
