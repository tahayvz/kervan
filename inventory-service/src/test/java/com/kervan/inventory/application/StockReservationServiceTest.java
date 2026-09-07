package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.Reservation;
import com.kervan.inventory.domain.model.ReservationLine;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.port.InventoryEventPublisher;
import com.kervan.inventory.domain.port.ReservationRepository;
import com.kervan.inventory.domain.port.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StockReservationService")
class StockReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String ORDER = "order-1";

    private StockRepository stockRepository;
    private ReservationRepository reservationRepository;
    private InventoryEventPublisher events;
    private StockReservationService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(StockRepository.class);
        reservationRepository = mock(ReservationRepository.class);
        events = mock(InventoryEventPublisher.class);
        when(reservationRepository.findByOrderId(anyString())).thenReturn(Optional.empty());
        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), "res-1"));
        service = new StockReservationService(stockRepository, reservationRepository, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Reservation withId(Reservation reservation, String id) {
        return new Reservation(id, reservation.orderId(), reservation.lines(),
                reservation.status(), reservation.createdAt(), reservation.updatedAt());
    }

    private void stockOnHand(StockItem... items) {
        when(stockRepository.lockAll(anyCollection())).thenReturn(List.of(items));
    }

    private static List<ReservationLine> lines(String sku, int quantity) {
        return List.of(new ReservationLine(sku, quantity));
    }

    @Test
    @DisplayName("yeterli stok varsa ayrılır ve olay yazılır")
    void reservesWhenStockIsAvailable() {
        stockOnHand(new StockItem("SKU-1", 10, 0));

        service.reserve(ORDER, lines("SKU-1", 3));

        ArgumentCaptor<Collection<StockItem>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(stockRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(item -> {
            assertThat(item.available()).isEqualTo(7);
            assertThat(item.reserved()).isEqualTo(3);
        });
        verify(events).stockReserved(ORDER, "res-1", NOW);
    }

    @Test
    @DisplayName("stok yetmezse hiçbir kalem ayrılmaz")
    void reservesNothingWhenOneLineIsShort() {
        stockOnHand(new StockItem("SKU-1", 10, 0), new StockItem("SKU-2", 1, 0));

        service.reserve(ORDER, List.of(
                new ReservationLine("SKU-1", 2),
                new ReservationLine("SKU-2", 5)));

        // "Ya hep ya hiç": ilk kalem yeterliydi ama ikincisi değil. Birincisini
        // ayırıp bırakmak, müşteriye satılmayan bir ürünü kilitlemek olurdu.
        verify(stockRepository, never()).saveAll(anyCollection());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("stok yetmezse başarısızlık olayı yine de yazılır")
    void writesFailureEventWhenStockIsShort() {
        stockOnHand(new StockItem("SKU-1", 1, 0));

        service.reserve(ORDER, lines("SKU-1", 5));

        // İstisna dışarı taşsaydı transaction geri alınır ve bu olay da silinirdi;
        // saga cevap beklerken asılı kalırdı.
        verify(events).stockReservationFailed(eq(ORDER), anyString(), eq(NOW));
        verify(events, never()).stockReserved(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("bilinmeyen SKU stok yokmuş gibi ele alınır")
    void treatsUnknownSkuAsOutOfStock() {
        stockOnHand(new StockItem("SKU-1", 10, 0));

        service.reserve(ORDER, lines("SKU-BILINMEYEN", 1));

        verify(events).stockReservationFailed(eq(ORDER), anyString(), eq(NOW));
        verify(stockRepository, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("aynı komut ikinci kez gelirse stok tekrar düşmez")
    void ignoresDuplicateReserveCommand() {
        when(reservationRepository.findByOrderId(ORDER)).thenReturn(Optional.of(
                new Reservation("res-1", ORDER, lines("SKU-1", 3),
                        com.kervan.inventory.domain.model.ReservationStatus.ACTIVE, NOW, NOW)));

        service.reserve(ORDER, lines("SKU-1", 3));

        // Teslimat en az bir kez; ikinci geliş stoğu ikinci kez düşürmemeli.
        verify(stockRepository, never()).saveAll(anyCollection());
        verify(events, never()).stockReserved(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("telafi tutulan stoğu geri verir")
    void releaseReturnsReservedStock() {
        when(reservationRepository.findByOrderId(ORDER)).thenReturn(Optional.of(
                new Reservation("res-1", ORDER, lines("SKU-1", 3),
                        com.kervan.inventory.domain.model.ReservationStatus.ACTIVE, NOW, NOW)));
        stockOnHand(new StockItem("SKU-1", 7, 3));

        service.release(ORDER);

        ArgumentCaptor<Collection<StockItem>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(stockRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(item -> {
            assertThat(item.available()).isEqualTo(10);
            assertThat(item.reserved()).isZero();
        });
        verify(events).stockReleased(ORDER, "res-1", NOW);
    }

    @Test
    @DisplayName("telafi ikinci kez gelirse stok iki kez geri verilmez")
    void ignoresDuplicateReleaseCommand() {
        when(reservationRepository.findByOrderId(ORDER)).thenReturn(Optional.of(
                new Reservation("res-1", ORDER, lines("SKU-1", 3),
                        com.kervan.inventory.domain.model.ReservationStatus.RELEASED, NOW, NOW)));

        service.release(ORDER);

        // Aksi hâlde aynı miktar satılabilire iki kez eklenir: yoktan stok.
        verify(stockRepository, never()).saveAll(anyCollection());
        verify(events, never()).stockReleased(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("hiç ayırma yapılmamışsa telafi sessizce geçer")
    void releaseIsANoOpWhenNothingWasReserved() {
        when(reservationRepository.findByOrderId(ORDER)).thenReturn(Optional.empty());

        service.release(ORDER);

        // Stok baştan yetmemiş olabilir; telafi komutu yine de gelir ve hata değildir.
        verify(stockRepository, never()).saveAll(anyCollection());
        verify(events, never()).stockReleased(anyString(), anyString(), any());
    }
}
