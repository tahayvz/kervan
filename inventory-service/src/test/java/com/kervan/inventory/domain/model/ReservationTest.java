package com.kervan.inventory.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Reservation")
class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-03-10T12:05:00Z");

    private Reservation active() {
        return Reservation.active("order-1", List.of(new ReservationLine("SKU-1", 2)), NOW);
    }

    @Test
    @DisplayName("yeni ayırma etkin durumda başlar")
    void startsActive() {
        assertThat(active().isActive()).isTrue();
        assertThat(active().status()).isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    @DisplayName("geri bırakma durumu değiştirir ve zamanı günceller")
    void releaseChangesStatus() {
        Reservation released = active().released(LATER);

        assertThat(released.isActive()).isFalse();
        assertThat(released.updatedAt()).isEqualTo(LATER);
        assertThat(released.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("aynı ayırma iki kez geri bırakılamaz")
    void cannotReleaseTwice() {
        // Telafi komutu en az bir kez gelir; ikinci kez işlenirse aynı miktar
        // satılabilire iki kez eklenir ve yoktan stok yaratılır.
        Reservation released = active().released(LATER);

        assertThatThrownBy(() -> released.released(LATER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zaten geri bırakılmış");
    }

    @Test
    @DisplayName("kalemsiz ayırma olmaz")
    void requiresAtLeastOneLine() {
        assertThatThrownBy(() -> Reservation.active("order-1", List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("kalem listesi dışarıdan değiştirilemez")
    void copiesLines() {
        var mutable = new java.util.ArrayList<>(List.of(new ReservationLine("SKU-1", 2)));
        Reservation reservation = Reservation.active("order-1", mutable, NOW);

        mutable.clear();

        assertThat(reservation.lines()).hasSize(1);
    }
}
