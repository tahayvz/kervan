package com.kervan.order.infrastructure.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutboxMetrics")
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final int MAX_ATTEMPTS = 5;

    private SpringDataOutboxRepository repository;
    private SimpleMeterRegistry registry;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataOutboxRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(repository, registry,
                Clock.fixed(NOW, ZoneOffset.UTC), MAX_ATTEMPTS);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("bekleyen en eski kaydın yaşı saniye olarak yayınlanır")
    void publishesAgeOfOldestPendingRecord() {
        when(repository.countDeliverable(MAX_ATTEMPTS)).thenReturn(3L);
        when(repository.oldestDeliverableOccurredAt(MAX_ATTEMPTS)).thenReturn(NOW.minusSeconds(90));

        metrics.refresh();

        assertThat(gauge("kervan.outbox.pending")).isEqualTo(3);
        // Asıl sinyal budur: kuyrukta üç kayıt olması normaldir, en eskisinin
        // 90 saniyedir beklemesi normal değildir.
        assertThat(gauge("kervan.outbox.oldest.pending.age")).isEqualTo(90);
    }

    @Test
    @DisplayName("kuyruk boşalınca yaş sıfırlanır")
    void resetsAgeWhenQueueDrains() {
        when(repository.oldestDeliverableOccurredAt(MAX_ATTEMPTS)).thenReturn(NOW.minusSeconds(120));
        metrics.refresh();
        assertThat(gauge("kervan.outbox.oldest.pending.age")).isEqualTo(120);

        when(repository.oldestDeliverableOccurredAt(MAX_ATTEMPTS)).thenReturn(null);
        metrics.refresh();

        // Son bilinen değeri bırakmak, kuyruk boşaldıktan sonra da alarmın
        // çalmaya devam etmesi demekti.
        assertThat(gauge("kervan.outbox.oldest.pending.age")).isZero();
    }

    @Test
    @DisplayName("kenara alınmış kayıtlar deneme sınırıyla sorulur")
    void countsSetAsideRecordsWithConfiguredLimit() {
        when(repository.countStuck(MAX_ATTEMPTS)).thenReturn(2L);

        metrics.refresh();

        // Sınır elle yazılsaydı ayardan kayabilir ve metrik yayıncının gerçekte
        // neyi atladığını göstermezdi.
        assertThat(gauge("kervan.outbox.stuck")).isEqualTo(2);
    }

    @Test
    @DisplayName("kenara alınmış kayıtlar gecikme ölçümüne girmez")
    void excludesSetAsideRecordsFromLagMetrics() {
        metrics.refresh();

        // Yayıncının artık DENEMEDİĞİ kayıtlar kuyruğun parçası değildir. Sorgular
        // aynı deneme sınırını almak zorunda; almasalardı tek bir zehirli mesaj
        // yaşı sonsuza kadar büyütür, gecikme alarmı sürekli çalar ve susturulurdu.
        verify(repository).countDeliverable(MAX_ATTEMPTS);
        verify(repository).oldestDeliverableOccurredAt(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("saat ileri kayarsa yaş negatif olmaz")
    void neverReportsNegativeAge() {
        // Kayıt zamanı veritabanının saatinden gelir, yaş bu servisin saatiyle
        // hesaplanır. İkisi birkaç milisaniye kayabilir; negatif bir yaş
        // grafikte anlamsız bir sıçrama olurdu.
        when(repository.oldestDeliverableOccurredAt(MAX_ATTEMPTS)).thenReturn(NOW.plusSeconds(3));

        metrics.refresh();

        assertThat(gauge("kervan.outbox.oldest.pending.age")).isZero();
    }
}
