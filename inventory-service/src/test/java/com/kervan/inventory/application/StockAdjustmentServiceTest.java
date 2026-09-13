package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.model.StockNotFoundException;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
import com.kervan.inventory.domain.port.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StockAdjustmentService")
class StockAdjustmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String SKU = "SKU-1";
    private static final String ID = "adj-1";
    private static final String WHO = "yonetici-sub-123";

    private StockRepository stockRepository;
    private StockAdjustmentRepository adjustments;
    private StockAdjustmentService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(StockRepository.class);
        adjustments = mock(StockAdjustmentRepository.class);
        service = new StockAdjustmentService(stockRepository, adjustments,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stockIs(StockItem item) {
        when(stockRepository.lockAll(anyCollection())).thenReturn(List.of(item));
    }

    @Test
    @DisplayName("eksi düzeltme satılabiliri azaltır")
    void appliesNegativeDelta() {
        stockIs(new StockItem(SKU, 40, 5));
        when(adjustments.saveIfNew(any())).thenReturn(true);

        StockItem result = service.adjust(ID, SKU, -5, AdjustmentReason.DAMAGED, null, WHO);

        assertThat(result.available()).isEqualTo(35);
        // Ayrılmış miktara dokunulmaz: orada duran mal bir müşteriye söz verilmiştir.
        assertThat(result.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("stok kaydı YOKSA hata verir, kayıt AÇMAZ")
    void failsWhenStockRowMissing() {
        when(stockRepository.lockAll(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.adjust(ID, SKU, -1, AdjustmentReason.DAMAGED, null, WHO))
                .isInstanceOf(StockNotFoundException.class);

        // Mal kabulünden tek yapısal fark bu: kabul kaydı kendisi açar, düzeltme açamaz.
        // Var olmayan bir sayı düzeltilemez.
        verify(stockRepository, never()).createIfAbsent(SKU);
        verify(adjustments, never()).saveIfNew(any());
    }

    @Test
    @DisplayName("aynı düzeltme ikinci kez gelirse TEKRAR UYGULANMAZ")
    void isIdempotent() {
        stockIs(new StockItem(SKU, 40, 0));
        when(adjustments.saveIfNew(any())).thenReturn(false);

        StockItem result = service.adjust(ID, SKU, -5, AdjustmentReason.DAMAGED, null, WHO);

        verify(stockRepository, never()).saveAll(anyCollection());
        assertThat(result.available()).isEqualTo(40);
    }

    @Test
    @DisplayName("OTHER gerekçesi açıklamasız reddedilir")
    void requiresNoteForOther() {
        assertThatThrownBy(() ->
                service.adjust(ID, SKU, -1, AdjustmentReason.OTHER, "  ", WHO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("açıklama zorunludur");

        // Denetim de stoğu kilitlemeden ÖNCE yapılmalı: geçersiz bir istek için satır
        // tutmak, aynı SKU'ya gelen siparişleri sebepsiz bekletirdi.
        verify(stockRepository, never()).lockAll(anyCollection());
    }

    @Test
    @DisplayName("OTHER açıklamalıysa kabul edilir")
    void acceptsOtherWithNote() {
        stockIs(new StockItem(SKU, 10, 0));
        when(adjustments.saveIfNew(any())).thenReturn(true);

        assertThat(service.adjust(ID, SKU, -1, AdjustmentReason.OTHER, "vitrin numunesi", WHO)
                .available()).isEqualTo(9);
    }

    @Test
    @DisplayName("kim, ne zaman ve neden kaydedilir")
    void recordsWhoWhenAndWhy() {
        stockIs(new StockItem(SKU, 10, 0));
        when(adjustments.saveIfNew(any())).thenReturn(true);

        service.adjust(ID, SKU, -3, AdjustmentReason.SHRINKAGE, "depo sayımı", WHO);

        ArgumentCaptor<StockAdjustment> captor = ArgumentCaptor.forClass(StockAdjustment.class);
        verify(adjustments).saveIfNew(captor.capture());
        StockAdjustment saved = captor.getValue();
        assertThat(saved.adjustedBy()).isEqualTo(WHO);
        assertThat(saved.adjustedAt()).isEqualTo(NOW);
        assertThat(saved.reason()).isEqualTo(AdjustmentReason.SHRINKAGE);
        assertThat(saved.delta()).isEqualTo(-3);
        assertThat(saved.note()).isEqualTo("depo sayımı");
    }

    @Test
    @DisplayName("satılabiliri eksiye düşüren düzeltme reddedilir")
    void rejectsNegativeResult() {
        stockIs(new StockItem(SKU, 3, 0));
        when(adjustments.saveIfNew(any())).thenReturn(true);

        assertThatThrownBy(() ->
                service.adjust(ID, SKU, -5, AdjustmentReason.DAMAGED, null, WHO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(stockRepository, never()).saveAll(anyCollection());
    }
}
