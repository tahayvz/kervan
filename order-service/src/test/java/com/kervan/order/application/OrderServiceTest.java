package com.kervan.order.application;

import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.application.exception.OrderAccessDeniedException;
import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Caller;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.model.SagaState;
import com.kervan.order.domain.port.OrderMessagePublisher;
import com.kervan.order.domain.port.OrderRepository;
import com.kervan.order.domain.port.SagaRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderService")
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Caller CUSTOMER = Caller.customer("c-1");

    private OrderRepository orderRepository;
    private SagaRepository sagaRepository;
    private OrderMessagePublisher messages;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        sagaRepository = mock(SagaRepository.class);
        // Mesajın hangi biçimde serileştirildiği ve hangi konuya gittiği bu katmanın
        // işi değil; o AvroOrderMessagePublisherTest'te doğrulanır.
        messages = mock(OrderMessagePublisher.class);
        orderService = new OrderService(
                orderRepository,
                sagaRepository,
                messages,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PlaceOrderCommand command() {
        return new PlaceOrderCommand("TRY", List.of(
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

        Order result = orderService.placeOrder(command(), CUSTOMER);

        assertThat(result.id()).isEqualTo("order-1");
        assertThat(result.totalAmount().amount()).isEqualByComparingTo("200.00");
    }


    @Test
    @DisplayName("yayınlanan olay siparişin bilgilerini taşır")
    void placeOrder_shouldBuildEventFromSavedOrder() {
        repositoryAssignsId("order-1");

        orderService.placeOrder(command(), CUSTOMER);

        ArgumentCaptor<OrderPlaced> captor = ArgumentCaptor.forClass(OrderPlaced.class);
        verify(messages).orderPlaced(captor.capture(), eq(NOW));

        OrderPlaced event = captor.getValue();
        assertThat(event.orderId()).isEqualTo("order-1");
        assertThat(event.customerId()).isEqualTo("c-1");
        assertThat(event.currency()).isEqualTo("TRY");
        assertThat(event.totalAmount()).isEqualByComparingTo("200.00");
        assertThat(event.placedAt()).isEqualTo(NOW);
        assertThat(event.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo("p-1");
            assertThat(item.sku()).isEqualTo("SKU-1");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.unitPrice()).isEqualByComparingTo("100.00");
        });
    }

    @Test
    @DisplayName("sipariş alınırken saga başlatılır ve ilk komut gönderilir")
    void placeOrder_shouldStartTheSaga() {
        repositoryAssignsId("order-1");

        orderService.placeOrder(command(), CUSTOMER);

        // Saga ve ilk komut siparişle AYNI transaction'da yazılır. Ayrı olsalardı
        // araya giren bir çökme siparişi oluşturur ama saga'yı hiç başlatmazdı.
        ArgumentCaptor<OrderSaga> saga = ArgumentCaptor.forClass(OrderSaga.class);
        verify(sagaRepository).save(saga.capture());
        assertThat(saga.getValue().orderId()).isEqualTo("order-1");
        assertThat(saga.getValue().state()).isEqualTo(SagaState.STOCK_RESERVING);

        verify(messages).reserveStock(eq("order-1"), anyList(), eq(NOW));
    }

    @Test
    @DisplayName("sipariş kaydedilemezse outbox kaydı da yazılmaz")
    void placeOrder_shouldNotWriteOutboxWhenOrderSaveFails() {
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new RuntimeException("veritabanı erişilemiyor"));

        assertThatThrownBy(() -> orderService.placeOrder(command(), CUSTOMER))
                .isInstanceOf(RuntimeException.class);

        verify(messages, never()).orderPlaced(any(), any());
        verify(sagaRepository, never()).save(any());
    }

    @Test
    void placeOrder_shouldRejectEmptyLines() {
        PlaceOrderCommand empty = new PlaceOrderCommand("TRY", List.of());

        assertThatThrownBy(() -> orderService.placeOrder(empty, CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderRepository, never()).save(any());
        verify(messages, never()).orderPlaced(any(), any());
    }

    @Test
    void getOrder_shouldReturnStoredOrder() {
        Order stored = Order.place("c-1", List.of(
                new com.kervan.order.domain.model.OrderLine(
                        "p-1", "SKU-1", 1,
                        com.kervan.order.domain.model.Money.of("10.00", "TRY"))), NOW);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(stored));

        assertThat(orderService.getOrder("order-1", CUSTOMER).customerId()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("müşteri başkasının siparişini göremez")
    void getOrder_shouldDenyAccessToAnotherCustomersOrder() {
        Order otherPersons = Order.place("baskasi", List.of(
                new com.kervan.order.domain.model.OrderLine(
                        "p-1", "SKU-1", 1,
                        com.kervan.order.domain.model.Money.of("10.00", "TRY"))), NOW);
        when(orderRepository.findById("order-9")).thenReturn(Optional.of(otherPersons));

        assertThatThrownBy(() -> orderService.getOrder("order-9", CUSTOMER))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    @Test
    @DisplayName("yönetici her siparişi görebilir")
    void getOrder_shouldAllowAdminToReadAnyOrder() {
        Order otherPersons = Order.place("baskasi", List.of(
                new com.kervan.order.domain.model.OrderLine(
                        "p-1", "SKU-1", 1,
                        com.kervan.order.domain.model.Money.of("10.00", "TRY"))), NOW);
        when(orderRepository.findById("order-9")).thenReturn(Optional.of(otherPersons));

        assertThat(orderService.getOrder("order-9", Caller.admin("admin-1")).customerId())
                .isEqualTo("baskasi");
    }

    @Test
    @DisplayName("sipariş sahibi çağırandan alınır, komuttan değil")
    void placeOrder_shouldTakeOwnerFromCaller() {
        repositoryAssignsId("order-1");

        orderService.placeOrder(command(), Caller.customer("gercek-musteri"));

        org.mockito.ArgumentCaptor<Order> captor =
                org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo("gercek-musteri");
    }

    @Test
    void getOrder_shouldThrowWhenMissing() {
        when(orderRepository.findById("yok")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder("yok", CUSTOMER))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("yok");
    }
}
