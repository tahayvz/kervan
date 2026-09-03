package com.kervan.order.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Order aggregate")
class OrderTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private OrderLine line(String sku, int quantity, String unitPrice) {
        return new OrderLine("p-" + sku, sku, quantity, Money.of(unitPrice, "TRY"));
    }

    @Test
    void place_shouldSumLineTotals() {
        Order order = Order.place("c-1",
                List.of(line("A", 2, "100.00"), line("B", 3, "50.00")), NOW);

        assertThat(order.totalAmount().amount()).isEqualByComparingTo("350.00");
    }

    @Test
    void place_shouldStartInPlacedStatus() {
        Order order = Order.place("c-1", List.of(line("A", 1, "10.00")), NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void place_shouldRejectEmptyOrder() {
        assertThatThrownBy(() -> Order.place("c-1", List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en az bir satır");
    }

    @Test
    void place_shouldRejectMixedCurrencies() {
        List<OrderLine> lines = List.of(
                new OrderLine("p-1", "A", 1, Money.of("10.00", "TRY")),
                new OrderLine("p-2", "B", 1, Money.of("10.00", "USD")));

        assertThatThrownBy(() -> Order.place("c-1", lines, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Farklı para birimleri");
    }

    @Test
    void lines_shouldNotBeModifiableThroughTheReturnedList() {
        Order order = Order.place("c-1", List.of(line("A", 1, "10.00")), NOW);

        order.lines().clear();

        assertThat(order.lines()).hasSize(1);
    }

    @Test
    void changeStatus_shouldMoveToAllowedStatus() {
        Order order = Order.place("c-1", List.of(line("A", 1, "10.00")), NOW);

        order.changeStatus(OrderStatus.CONFIRMED, NOW.plusSeconds(60));

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void changeStatus_shouldRejectDisallowedTransition() {
        Order order = Order.place("c-1", List.of(line("A", 1, "10.00")), NOW);

        assertThatThrownBy(() -> order.changeStatus(OrderStatus.DELIVERED, NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("Geçersiz durum geçişi");
    }

    @Test
    void changeStatus_shouldLeaveStatusUntouchedWhenRejected() {
        Order order = Order.place("c-1", List.of(line("A", 1, "10.00")), NOW);

        assertThatThrownBy(() -> order.changeStatus(OrderStatus.DELIVERED, NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);

        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
    }

    // --- durum makinesi: tüm geçiş matrisi ---

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
            new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(OrderStatus.PLACED, EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED));
        ALLOWED.put(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        ALLOWED.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED));
        ALLOWED.put(OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    static Stream<Arguments> allTransitionPairs() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values())
                        .map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allTransitionPairs")
    @DisplayName("her geçiş çifti tabloyla birebir uyuşur")
    void canTransitionTo_shouldMatchTable(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @Test
    void transitionTable_shouldCoverEveryStatus() {
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    @Test
    void deliveredAndCancelled_shouldBeFinal() {
        assertThat(OrderStatus.DELIVERED.isFinal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isFinal()).isTrue();
        assertThat(OrderStatus.PLACED.isFinal()).isFalse();
    }
}
