package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.model.StockReceipt;
import com.kervan.inventory.domain.port.StockReceiptRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StockReceiptService")
class StockReceiptServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String SKU = "SKU-1";
    private static final String RECEIPT = "receipt-1";

    private StockRepository stockRepository;
    private StockReceiptRepository receiptRepository;
    private StockReceiptService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(StockRepository.class);
        receiptRepository = mock(StockReceiptRepository.class);
        service = new StockReceiptService(stockRepository, receiptRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("gelen miktarı mevcut stoğa EKLER, üzerine yazmaz")
    void addsToExistingStock() {
        given(new StockItem(SKU, 40, 5));
        when(receiptRepository.saveIfNew(any())).thenReturn(true);

        StockItem result = service.receive(RECEIPT, SKU, 10);

        assertThat(result.available()).isEqualTo(50);
        // Ayrılmış miktara dokunulmaz: gelen mal kimseye tutulmuş değildir.
        assertThat(result.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("hiç görülmemiş SKU için önce satır açar")
    void opensRowForUnknownSku() {
        given(new StockItem(SKU, 0, 0));
        when(receiptRepository.saveIfNew(any())).thenReturn(true);

        StockItem result = service.receive(RECEIPT, SKU, 25);

        // Satır açma, kilitli okumadan ÖNCE olmalı: lockAll yalnızca VAR OLAN satırı
        // kilitler, olmayan için boş döner.
        verify(stockRepository).createIfAbsent(SKU);
        assertThat(result.available()).isEqualTo(25);
    }

    @Test
    @DisplayName("aynı makbuz ikinci kez gelirse miktarı TEKRAR EKLEMEZ ve kilit almaz")
    void isIdempotentForRepeatedReceipt() {
        when(stockRepository.find(SKU)).thenReturn(Optional.of(new StockItem(SKU, 40, 0)));
        // Depo "bu makbuz zaten vardı" diyor.
        when(receiptRepository.saveIfNew(any())).thenReturn(false);

        StockItem result = service.receive(RECEIPT, SKU, 10);

        // Stok yazılmaz ve mevcut hâli döner. Cevap yine başarılıdır: idempotent bir uç
        // "istediğin durum sağlandı" der, "bunu daha önce de söylemiştin" demez.
        verify(stockRepository, never()).saveAll(anyCollection());
        assertThat(result.available()).isEqualTo(40);

        // Ve KİLİT ALMAZ. Yazma yapmayan bir isteğin satırı tutması, aynı SKU'ya
        // gelen siparişleri sebepsiz bekletirdi: bir yeniden denemenin bedeli, ilk
        // isteğin bedelinden yüksek olmamalı.
        verify(stockRepository, never()).lockAll(anyCollection());
    }

    @Test
    @DisplayName("makbuzu istemcinin verdiği kimlikle ve sabit saatle yazar")
    void writesReceiptWithClientIdAndClock() {
        given(new StockItem(SKU, 0, 0));
        when(receiptRepository.saveIfNew(any())).thenReturn(true);

        service.receive(RECEIPT, SKU, 7);

        ArgumentCaptor<StockReceipt> captor = ArgumentCaptor.forClass(StockReceipt.class);
        verify(receiptRepository).saveIfNew(captor.capture());
        assertThat(captor.getValue().receiptId()).isEqualTo(RECEIPT);
        assertThat(captor.getValue().quantity()).isEqualTo(7);
        // Saat enjekte edilir: test zamanı sabit, dolayısıyla iddia da sabit.
        assertThat(captor.getValue().receivedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("okuma KİLİT ALMAZ")
    void readDoesNotLock() {
        when(stockRepository.find(SKU)).thenReturn(Optional.of(new StockItem(SKU, 3, 1)));

        assertThat(service.find(SKU)).contains(new StockItem(SKU, 3, 1));

        // lockAll, arkasından yazma gelecek okumalar içindir. Görüntüleme isteğinin
        // satırı tutması, aynı SKU'ya gelen siparişleri sebepsiz bekletirdi.
        verify(stockRepository, never()).lockAll(anyCollection());
    }

    /** Kilitli okuma her çağrıldığında verilen stoğu döndürür. */
    private void given(StockItem item) {
        when(stockRepository.lockAll(anyCollection())).thenReturn(List.of(item));
    }
}
