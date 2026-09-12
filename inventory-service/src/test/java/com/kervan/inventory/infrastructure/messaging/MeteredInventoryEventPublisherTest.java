package com.kervan.inventory.infrastructure.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("MeteredInventoryEventPublisher")
class MeteredInventoryEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private AvroInventoryEventPublisher delegate;
    private SimpleMeterRegistry registry;
    private MeteredInventoryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(AvroInventoryEventPublisher.class);
        registry = new SimpleMeterRegistry();
        publisher = new MeteredInventoryEventPublisher(delegate, registry);
    }

    private double count(String result) {
        return registry.get("kervan.stock.reservations").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("başarılı ayırma yalnızca kendi etiketini artırır")
    void countsSuccessUnderItsOwnLabel() {
        publisher.stockReserved("order-1", "res-1", NOW);

        assertThat(count("reserved")).isEqualTo(1);
        assertThat(count("rejected")).isZero();
    }

    @Test
    @DisplayName("stok yetmemesi ayrı sayılır — hata değil, sonuçtur")
    void countsRejectionSeparately() {
        publisher.stockReservationFailed("order-1", "SKU-1 icin stok yetersiz", NOW);

        assertThat(count("rejected")).isEqualTo(1);
        assertThat(count("reserved")).isZero();
    }

    @Test
    @DisplayName("olay her durumda asıl yayıncıya iletilir")
    void alwaysDelegates() {
        // Sarmalayıcı davranışı DEĞİŞTİRMEZ; yalnızca sayar. Delegasyonu
        // unutmak, ölçüm eklerken olay akışını sessizce kesmek olurdu.
        publisher.stockReserved("order-1", "res-1", NOW);
        publisher.stockReservationFailed("order-2", "yetersiz", NOW);
        publisher.stockReleased("order-3", "res-3", NOW);

        verify(delegate).stockReserved("order-1", "res-1", NOW);
        verify(delegate).stockReservationFailed("order-2", "yetersiz", NOW);
        verify(delegate).stockReleased("order-3", "res-3", NOW);
    }

    @Test
    @DisplayName("sebep etiket olmaz: serbest metin sınırsız zaman serisi açardı")
    void doesNotTagByReason() {
        publisher.stockReservationFailed("order-1", "SKU-1 icin stok yetersiz", NOW);
        publisher.stockReservationFailed("order-2", "SKU-2 icin stok yetersiz", NOW);

        // İki farklı sebep, TEK zaman serisi. Sebep etiket olsaydı her farklı
        // metin yeni bir seri açardı ve metrik deposu şişerdi.
        assertThat(registry.find("kervan.stock.reservations").tag("result", "rejected").counters())
                .hasSize(1);
        assertThat(count("rejected")).isEqualTo(2);
    }
}
