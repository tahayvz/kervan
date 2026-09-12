package com.kervan.order.infrastructure.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox kuyruğunun sağlığını ölçer.
 *
 * <h2>Neden üç ayrı sayı?</h2>
 * <ul>
 *   <li><b>Bekleyen sayısı</b> tek başına yanıltıcıdır: kuyrukta tek kayıt olabilir
 *       ama o kayıt iki saattir orada duruyor olabilir.</li>
 *   <li><b>En eskinin yaşı</b> gecikmeyi gösterir. Alarm buna kurulur.</li>
 *   <li><b>Kenara alınmış kayıt sayısı</b>, deneme sınırına takılanları görünür kılar.
 *       Onlar kuyruğu tıkamaz — tasarım gereği — ama kimse bakmazsa sessizce
 *       kaybolur.</li>
 * </ul>
 *
 * <p><b>İlk iki ölçüm kenara alınmış kayıtları saymaz.</b> Sayması, tek bir zehirli
 * mesajın gecikme grafiğini sonsuza kadar yukarı çekmesi demekti: kuyruk normal
 * akarken alarm sürekli çalar, susturulur ve sonraki gerçek birikme fark edilmezdi.
 * Filtre yayıncınınkiyle ({@code lockDeliverable}) birebir aynı tutuluyor.
 *
 * <h2>Neden Debezium devredeyken kapalı?</h2>
 * Bu ölçümlerin hepsi {@code published_at} sütununa dayanır ve o sütunu yalnızca
 * uygulama içi yayıncı doldurur. Debezium değişiklik günlüğünü okur, tabloya geri
 * yazmaz; orada tüm satırlar sonsuza kadar "yayınlanmamış" görünür ve "bekleyen
 * sayısı" aslında "tablodaki satır sayısı" olurdu. <b>Yanlış bir metrik,
 * metriksizlikten kötüdür:</b> alarm kurarsın, alarm hep çalar, sonra susturursun,
 * sonra gerçek arıza da sessiz kalır.
 *
 * <p>Debezium devredeyken gecikme Debezium'un kendi metriklerinden okunur
 * ({@code MilliSecondsBehindSource}); tablonun kendisi yaşa göre temizlenir
 * ({@code OutboxCleaner}).
 *
 * <h2>Neden zamanlanmış yenileme, doğrudan sorgu değil?</h2>
 * Bir gauge her <b>kazıma</b> (scrape) anında okunur. Gauge'u doğrudan sorguya
 * bağlasaydık, veritabanına kaç sorgu gideceğine Prometheus'un ayarı karar verirdi —
 * kazıma aralığı, kopya sayısı, elle atılan istekler. Kendi aralığımızda yenilemek
 * o maliyeti bizde tutar.
 *
 * <p>Üç sorgu tek transaction'da değil: metrik için birbiriyle tam tutarlı olmaları
 * gerekmez, aradaki fark bir yenileme turu kadardır.
 */
@Component
@ConditionalOnProperty(name = "kervan.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
class OutboxMetrics {

    private final SpringDataOutboxRepository repository;
    private final Clock clock;
    private final int maxAttempts;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    OutboxMetrics(SpringDataOutboxRepository repository,
                  MeterRegistry registry,
                  Clock clock,
                  @Value("${kervan.outbox.max-attempts}") int maxAttempts) {
        this.repository = repository;
        this.clock = clock;
        this.maxAttempts = maxAttempts;

        Gauge.builder("kervan.outbox.pending", pending, AtomicLong::doubleValue)
                .description("Gonderilmeyi bekleyen outbox kaydi (kenara alinanlar haric)")
                .register(registry);

        Gauge.builder("kervan.outbox.oldest.pending.age", oldestPendingSeconds, AtomicLong::doubleValue)
                .description("Bekleyen en eski outbox kaydinin yasi (kenara alinanlar haric)")
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("kervan.outbox.stuck", stuck, AtomicLong::doubleValue)
                .description("Deneme sinirina takilip kenara alinmis outbox kaydi sayisi")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${kervan.outbox.metrics.refresh-interval-ms:15000}")
    public void refresh() {
        // Üçü de AYNI deneme sınırını kullanır: "kuyrukta ne var" ile "yayıncı neyi
        // deniyor" aynı cevabı vermeli.
        pending.set(repository.countDeliverable(maxAttempts));
        stuck.set(repository.countStuck(maxAttempts));

        Instant oldest = repository.oldestDeliverableOccurredAt(maxAttempts);
        // Bekleyen kayıt yoksa yaş sıfırdır. Son bilinen değeri bırakmak, kuyruk
        // boşaldıktan sonra da alarmın çalmaya devam etmesi demekti.
        oldestPendingSeconds.set(oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, clock.instant()).getSeconds()));
    }
}
