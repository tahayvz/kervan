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
