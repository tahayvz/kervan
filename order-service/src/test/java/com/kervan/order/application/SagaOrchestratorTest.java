package com.kervan.order.application;

import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderLine;
import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.model.OrderStatus;
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
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saga'nın her adımı ve telafi yolu.
 *
 * <p>Kafka yok: akış mantığı mesajlaşmadan ayrı durduğu için mock port'larla test
 * edilebiliyor. Mesajların gerçekten Kafka'ya ulaştığı ayrı bir testte doğrulanır.
 */
@DisplayName("SagaOrchestrator")
class SagaOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String ORDER = "order-1";

    private SagaRepository sagas;
    private OrderRepository orders;
    private OrderMessagePublisher messages;
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sagas = mock(SagaRepository.class);
        orders = mock(OrderRepository.class);
        messages = mock(OrderMessagePublisher.class);
        when(orders.findById(ORDER)).thenReturn(Optional.of(order()));
        when(sagas.save(any(OrderSaga.class))).thenAnswer(i -> i.getArgument(0));
        orchestrator = new SagaOrchestrator(sagas, orders, messages,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order() {
        return Order.place("c-1", List.of(new OrderLine("p-1", "SKU-1", 2,
                new Money(new BigDecimal("50.00"), Currency.getInstance("TRY")))), NOW);
    }

    private void sagaIn(SagaState state, String reservationId, String paymentId) {
        when(sagas.lockByOrderId(ORDER)).thenReturn(Optional.of(
                new OrderSaga(ORDER, state, reservationId, paymentId, NOW, NOW)));
    }

    private OrderSaga savedSaga() {
        ArgumentCaptor<OrderSaga> captor = ArgumentCaptor.forClass(OrderSaga.class);
        verify(sagas).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("stok ayrılınca ödeme istenir")
    void stockReservedAsksForPayment() {
        sagaIn(SagaState.STOCK_RESERVING, null, null);

        orchestrator.onStockReserved(ORDER, "res-1");

        verify(messages).processPayment(eq(ORDER), eq("c-1"), any(Money.class), eq(NOW));
        OrderSaga saga = savedSaga();
        assertThat(saga.state()).isEqualTo(SagaState.PAYMENT_PROCESSING);
        // Telafi için saklanır: hangi ayırmanın bırakılacağı bununla bilinir.
        assertThat(saga.reservation()).contains("res-1");
    }

    @Test
    @DisplayName("stok yetmezse sipariş doğrudan iptal edilir")
    void stockFailureCancelsTheOrder() {
        sagaIn(SagaState.STOCK_RESERVING, null, null);

        orchestrator.onStockReservationFailed(ORDER, "stok yok");

        // Henüz yapılmış bir şey yok; telafi adımı gerekmez.
        verify(messages, never()).releaseStock(anyString(), anyString(), any());
        verify(messages).orderCancelled(eq(ORDER), anyString(), eq(NOW));
        assertThat(savedSaga().state()).isEqualTo(SagaState.CANCELLED);
        assertThat(savedOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("ödeme alınınca sipariş onaylanır")
    void paymentProcessedConfirmsTheOrder() {
        sagaIn(SagaState.PAYMENT_PROCESSING, "res-1", null);

        orchestrator.onPaymentProcessed(ORDER, "pay-1");

        verify(messages).orderConfirmed(ORDER, NOW);
        assertThat(savedOrder().status()).isEqualTo(OrderStatus.CONFIRMED);
        OrderSaga saga = savedSaga();
        assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.payment()).contains("pay-1");
    }

    @Test
    @DisplayName("ödeme başarısızsa önce stok geri bırakılır, sipariş henüz iptal edilmez")
    void paymentFailureReleasesStockFirst() {
        sagaIn(SagaState.PAYMENT_PROCESSING, "res-1", null);

        orchestrator.onPaymentFailed(ORDER, "limit aşıldı");

        verify(messages).releaseStock(ORDER, "res-1", NOW);
        // "İptal edildi" derken stoğun hâlâ tutuluyor olması, müşteriye satılmayan
        // ürünü kilitlemek olurdu. İptal telafi bitince gelir.
        verify(messages, never()).orderCancelled(anyString(), anyString(), any());
        assertThat(savedSaga().state()).isEqualTo(SagaState.STOCK_RELEASING);
    }

    @Test
    @DisplayName("telafi bitince sipariş iptal edilir")
    void stockReleasedCancelsTheOrder() {
        sagaIn(SagaState.STOCK_RELEASING, "res-1", null);

        orchestrator.onStockReleased(ORDER);

        verify(messages).orderCancelled(eq(ORDER), anyString(), eq(NOW));
        assertThat(savedSaga().state()).isEqualTo(SagaState.CANCELLED);
        assertThat(savedOrder().status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("tekrar gelen olay saga'yı ikinci kez ilerletmez")
    void duplicateEventIsIgnored() {
        // Saga zaten ödemeye geçmiş; StockReserved ikinci kez geliyor.
        sagaIn(SagaState.PAYMENT_PROCESSING, "res-1", null);

        orchestrator.onStockReserved(ORDER, "res-1");

        // Aksi hâlde ikinci kez ödeme istenirdi.
        verify(messages, never()).processPayment(anyString(), anyString(), any(), any());
        verify(sagas, never()).save(any());
    }

    @Test
    @DisplayName("bitmiş saga'ya gelen olay yok sayılır")
    void eventForFinishedSagaIsIgnored() {
        sagaIn(SagaState.COMPLETED, "res-1", "pay-1");

        orchestrator.onPaymentFailed(ORDER, "geç gelen olay");

        verify(messages, never()).releaseStock(anyString(), anyString(), any());
        verify(sagas, never()).save(any());
    }

    @Test
    @DisplayName("saga yoksa olay sessizce yok sayılır")
    void eventForUnknownSagaIsIgnored() {
        when(sagas.lockByOrderId(ORDER)).thenReturn(Optional.empty());

        orchestrator.onStockReserved(ORDER, "res-1");

        // Bu servise ait olmayan bir sipariş olabilir; istisna fırlatmak mesajın
        // sonsuza kadar yeniden teslim edilmesine yol açardı.
        verify(messages, never()).processPayment(anyString(), anyString(), any(), any());
        verify(sagas, never()).save(any());
    }

    private Order savedOrder() {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orders).save(captor.capture());
        return captor.getValue();
    }
}
