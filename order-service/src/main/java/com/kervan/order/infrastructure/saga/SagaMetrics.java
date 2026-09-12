package com.kervan.order.infrastructure.saga;

import com.kervan.order.domain.model.SagaState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Havada kalan saga sayısını duruma göre ölçer.
 *
 * <h2>Hangi soruyu cevaplıyor?</h2>
 * "Kaç sipariş yolun ortasında takıldı?" Bir saga bir cevap bekler: stok servisinden
 * ya da ödeme servisinden. Cevap hiç gelmezse saga o durumda kalır. Hiçbir hata
 * log'u oluşmaz — kimse hata vermedi, yalnızca kimse cevap vermedi. Bu metrik
 * olmadan o sessizlik görünmez.
 *
 * <p>Alarm sayıya değil <b>süreye</b> kurulur: "PAYMENT_PROCESSING'de bekleyen saga
 * sayısı 10 dakikadır düşmüyor". Anlık bir yığılma normaldir; inmeyen bir yığılma
 * değildir.
 *
 * <h2>Neden yalnızca bitmemiş durumlar?</h2>
 * {@code COMPLETED} ve {@code CANCELLED} satırları tabloda birikir; onları gauge
 * olarak yayınlamak "şu ana kadar kaç sipariş tamamlandı" demek olurdu — bu bir
 * gauge'un işi değil, sayacın (counter) işidir. Gauge <b>şu an</b> ne olduğunu
 * söyler; burada sorulan şey de o.
 */
@Component
class SagaMetrics {

    private final SpringDataSagaRepository repository;
    private final Map<SagaState, AtomicLong> inFlight = new EnumMap<>(SagaState.class);

    SagaMetrics(SpringDataSagaRepository repository, MeterRegistry registry) {
        this.repository = repository;

        for (SagaState state : SagaState.values()) {
            if (state.isFinal()) {
                continue;
            }
            AtomicLong holder = new AtomicLong();
            inFlight.put(state, holder);

            // Etiket değerleri enum'dan geliyor: kümesi sonlu ve küçük. Etiketi
            // sipariş kimliği gibi sınırsız bir alandan üretmek, her siparişe ayrı
            // zaman serisi açardı ve Prometheus'u yerdi.
            Gauge.builder("kervan.saga.in.flight", holder, AtomicLong::doubleValue)
                    .description("Cevap bekleyen saga sayisi")
                    .tag("state", state.name())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${kervan.saga.metrics.refresh-interval-ms:15000}")
    public void refresh() {
        Map<SagaState, Long> counts = new EnumMap<>(SagaState.class);
        for (Object[] row : repository.countByState()) {
            counts.put((SagaState) row[0], (Long) row[1]);
        }
        // Sorguda görünmeyen durum sıfırlanır. Son bilinen değeri bırakmak,
        // boşalmış bir durumun dolu görünmesi demekti.
        inFlight.forEach((state, holder) -> holder.set(counts.getOrDefault(state, 0L)));
    }
}
