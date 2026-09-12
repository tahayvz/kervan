package com.kervan.order.infrastructure.saga;

import com.kervan.order.domain.model.SagaState;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SagaMetrics")
class SagaMetricsTest {

    private SpringDataSagaRepository repository;
    private SimpleMeterRegistry registry;
    private SagaMetrics metrics;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataSagaRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new SagaMetrics(repository, registry);
    }

    private double inFlight(SagaState state) {
        return registry.get("kervan.saga.in.flight").tag("state", state.name()).gauge().value();
    }

    @Test
    @DisplayName("duruma göre bekleyen saga sayısı yayınlanır")
    void publishesCountPerState() {
        when(repository.countByState()).thenReturn(List.of(
                new Object[]{SagaState.STOCK_RESERVING, 2L},
                new Object[]{SagaState.PAYMENT_PROCESSING, 5L}));

        metrics.refresh();

        assertThat(inFlight(SagaState.STOCK_RESERVING)).isEqualTo(2);
        assertThat(inFlight(SagaState.PAYMENT_PROCESSING)).isEqualTo(5);
    }

    @Test
    @DisplayName("sorguda görünmeyen durum sıfırlanır")
    void resetsStatesMissingFromQuery() {
        when(repository.countByState()).thenReturn(
                List.<Object[]>of(new Object[]{SagaState.PAYMENT_PROCESSING, 4L}));
        metrics.refresh();
        assertThat(inFlight(SagaState.PAYMENT_PROCESSING)).isEqualTo(4);

        when(repository.countByState()).thenReturn(List.<Object[]>of());
        metrics.refresh();

        // Son bilinen değeri bırakmak, boşalmış bir durumun dolu görünmesi demekti.
        assertThat(inFlight(SagaState.PAYMENT_PROCESSING)).isZero();
    }

    @Test
    @DisplayName("bitmiş durumlar için gauge yok")
    void doesNotPublishFinalStates() {
        // COMPLETED satırları tabloda birikir; onları gauge olarak yayınlamak
        // "bugüne kadar kaç sipariş tamamlandı" demek olurdu. Gauge ŞU ANI
        // söyler; birikimli toplam sayacın (counter) işidir.
        assertThatThrownBy(() -> inFlight(SagaState.COMPLETED))
                .isInstanceOf(MeterNotFoundException.class);
        assertThatThrownBy(() -> inFlight(SagaState.CANCELLED))
                .isInstanceOf(MeterNotFoundException.class);
    }
}
