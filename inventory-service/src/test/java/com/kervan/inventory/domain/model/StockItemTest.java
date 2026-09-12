package com.kervan.inventory.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockItem")
class StockItemTest {

    private final StockItem item = new StockItem("SKU-1", 10, 0);

    @Test
    @DisplayName("ayırma miktarı satılabilirden ayrılmışa taşır")
    void reserveMovesQuantityBetweenBuckets() {
        StockItem reserved = item.reserve(3);

        assertThat(reserved.available()).isEqualTo(7);
        assertThat(reserved.reserved()).isEqualTo(3);
    }

    @Test
    @DisplayName("toplam miktar ayırmayla değişmez")
    void totalStaysTheSameAcrossReserve() {
        StockItem reserved = item.reserve(4);

        // Ayırma stok yaratmaz ve yok etmez; yalnızca yer değiştirir.
        assertThat(reserved.available() + reserved.reserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("satılabilirden fazlası ayrılamaz")
    void cannotReserveMoreThanAvailable() {
        assertThatThrownBy(() -> item.reserve(11))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-1");
    }

    @Test
    @DisplayName("tamamı ayrılabilir")
    void canReserveEverything() {
        StockItem reserved = item.reserve(10);

        assertThat(reserved.available()).isZero();
        assertThat(reserved.reserved()).isEqualTo(10);
    }

    @Test
    @DisplayName("geri bırakma miktarı satılabilire döndürür")
    void releaseMovesQuantityBack() {
        StockItem released = item.reserve(4).release(4);

        assertThat(released.available()).isEqualTo(10);
        assertThat(released.reserved()).isZero();
    }

    @Test
    @DisplayName("tutulandan fazlası geri bırakılamaz")
    void cannotReleaseMoreThanReserved() {
        // Aksi hâlde yoktan stok yaratılırdı: aynı miktar iki kez satılabilire eklenir.
        assertThatThrownBy(() -> item.reserve(2).release(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tutulandan fazlası");
    }

    @Test
    @DisplayName("miktar pozitif olmalı")
    void rejectsNonPositiveQuantities() {
        assertThatThrownBy(() -> item.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.reserve(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.release(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.receive(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.receive(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("mal kabulü satılabilire EKLER, ayrılmışa dokunmaz")
    void receiveAddsToAvailable() {
        StockItem afterReserve = item.reserve(4);   // available 6, reserved 4

        StockItem received = afterReserve.receive(10);

        assertThat(received.available()).isEqualTo(16);
        assertThat(received.reserved()).isEqualTo(4);
    }

    @Test
    @DisplayName("iki ayrı mal kabulü de sayılır (atama olsaydı biri kaybolurdu)")
    void receivesAccumulate() {
        // ADR-0019'un asıl gerekçesi: "stok artık N olsun" deseydik, aynı anda gelen
        // iki girişten biri sessizce buharlaşırdı.
        assertThat(item.receive(5).receive(7).available()).isEqualTo(22);
    }

    @Test
    @DisplayName("taşma sessizce negatife dönmez, hata verir")
    void receiveRejectsOverflow() {
        // Math.addExact olmasaydı sonuç negatife sarar, StockItem kurucusu da bunu
        // reddederdi -- ama hata mesajı "available negatif olamaz" derdi ve gerçek
        // sebebi (taşma) hiçbir yerde görünmezdi.
        StockItem nearLimit = new StockItem("SKU-1", Integer.MAX_VALUE - 1, 0);

        assertThatThrownBy(() -> nearLimit.receive(2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("negatif miktarla oluşturulamaz")
    void rejectsNegativeQuantitiesAtConstruction() {
        assertThatThrownBy(() -> new StockItem("SKU-1", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockItem("SKU-1", 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
