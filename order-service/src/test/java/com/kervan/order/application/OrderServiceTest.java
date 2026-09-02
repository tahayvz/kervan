package com.kervan.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OrderRepository;
import com.kervan.order.domain.port.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderService")
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private OrderRepository orderRepository;
    private OutboxRepository outboxRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        orderService = new OrderService(
                orderRepository,
                outboxRepository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PlaceOrderCommand command() {
        return new PlaceOrderCommand("c-1", "TRY", List.of(
                new PlaceOrderCommand.Line("p-1", "SKU-1", 2, new BigDecimal("100.00"))));
    }

    /** Kaydedilen siparişi kimlik atanmış hâliyle geri veren repository taklidi. */
    private void repositoryAssignsId(String id) {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order incoming = invocation.getArgument(0);
            return Order.restore(id, incoming.customerId(), incoming.lines(),
                    incoming.totalAmount(), incoming.status(),
                    incoming.placedAt(), incoming.updatedAt());
        });
    }

    @Test
    void placeOrder_shouldPersistOrder() {
        repositoryAssignsId("order-1");

        Order result = orderService.placeOrder(command());

        assertThat(result.id()).isEqualTo("order-1");
        assertThat(result.totalAmount().amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void placeOrder_shouldWriteOutboxMessageForTheSavedOrder() {
        repositoryAssignsId("order-1");

        orderService.placeOrder(command());

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());

        OutboxMessage message = captor.getValue();
        assertThat(message.aggregateType()).isEqualTo("Order");
        assertThat(message.aggregateId()).isEqualTo("order-1");
        assertThat(message.eventType()).isEqualTo("OrderPlaced");
        assertThat(message.occurredAt()).isEqualTo(NOW);
        assertThat(message.isPublished()).isFalse();
    }

    @Test
    void placeOrder_shouldSerialiseEventPayloadWithOrderDetails() {
        repositoryAssignsId("order-1");

        orderService.placeOrder(command());

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());

        assertThat(captor.getValue().payload())
                .contains("\"orderId\":\"order-1\"")
                .contains("\"customerId\":\"c-1\"")
                .contains("\"currency\":\"TRY\"")
                .contains("\"sku\":\"SKU-1\"");
    }

    @Test
    @DisplayName("sipariş kaydedilemezse outbox kaydı da yazılmaz")
    void placeOrder_shouldNotWriteOutboxWhenOrderSaveFails() {
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("veritabanı erişilemiyor"));

        assertThatThrownBy(() -> orderService.placeOrder(command()))
                .isInstanceOf(RuntimeException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void placeOrder_shouldRejectEmptyLines() {
        PlaceOrderCommand empty = new PlaceOrderCommand("c-1", "TRY", List.of());

        assertThatThrownBy(() -> orderService.placeOrder(empty))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void getOrder_shouldReturnStoredOrder() {
        Order stored = Order.place("c-1", List.of(
                new com.kervan.order.domain.model.OrderLine(
                        "p-1", "SKU-1", 1,
                        com.kervan.order.domain.model.Money.of("10.00", "TRY"))), NOW);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(stored));

        assertThat(orderService.getOrder("order-1").customerId()).isEqualTo("c-1");
    }

    @Test
    void getOrder_shouldThrowWhenMissing() {
        when(orderRepository.findById("yok")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder("yok"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("yok");
    }
}
