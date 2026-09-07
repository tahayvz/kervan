package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbox tablosundan eski kayıtları siler.
 *
 * <h2>Neden gerekli?</h2>
 * Outbox bir kuyruktur ama tablo kendini boşaltmaz. Her sipariş bir satır bırakır ve
 * hiçbiri silinmezse tablo sınırsız büyür. Büyüyen tablo yalnızca disk yemez:
 * yayıncının sorgusu, indeksler ve yedekleme süresi de onunla birlikte büyür.
 *
 * <h2>Ölçüt kim taşıyorsa ona göre değişir</h2>
 * <ul>
 *   <li><b>Uygulama içi yayıncı</b> teslim ettiği kaydı {@code publishedAt} ile
 *       damgalar. O yüzden yalnızca damgalı kayıtlar silinir. Damgasız eski bir kayıt
 *       gönderilememiş demektir; silinmesi olayın kaybolması olurdu.</li>
 *   <li><b>Debezium</b> hiçbir şey damgalamaz — satırı veritabanının değişiklik
 *       günlüğünden okur, tabloya dokunmaz. Orada "yayınlandı mı" diye bakılacak bir
 *       işaret yoktur, tek ölçüt yaştır.</li>
 * </ul>
 * Hangisinin çalıştığını {@code kervan.outbox.publisher.enabled} söyler; temizlik de
 * ölçütünü ondan alır. İki ayrı anahtar olsaydı biri değişip diğeri unutulabilirdi.
 *
 * <h2>Yaş ölçütünün riski</h2>
 * Debezium modunda, Debezium saklama penceresinden daha uzun süre durursa henüz
 * okumadığı satırlar silinir ve o olaylar kaybolur. Pencerenin varsayılanı bu yüzden
 * geniş (7 gün) ve Debezium'un durup durmadığı ayrıca izlenmelidir — replication
 * slot'un gecikmesi bunu söyler ({@code infra/docker/debezium/README.md}).
 *
 * <p>Temizlik parça parça yapılır. Sınırsız tek bir {@code DELETE}, tablo büyümüşse
 * milyonlarca satırı tek transaction'da siler ve sipariş yazan istekler o süre boyunca
 * bekler. Bir turda bitmeyen iş bir sonraki turda devam eder.
 */
@Component
@ConditionalOnProperty(name = "kervan.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final OutboxRepository outboxRepository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final boolean publisherEnabled;

    OutboxCleaner(OutboxRepository outboxRepository,
                  Clock clock,
                  @Value("${kervan.outbox.cleanup.retention}") Duration retention,
                  @Value("${kervan.outbox.cleanup.batch-size}") int batchSize,
                  @Value("${kervan.outbox.publisher.enabled:true}") boolean publisherEnabled) {
        this.outboxRepository = outboxRepository;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
        this.publisherEnabled = publisherEnabled;
    }

    @Scheduled(fixedDelayString = "${kervan.outbox.cleanup.interval-ms}")
    @Transactional
    public void deleteExpired() {
        Instant cutoff = clock.instant().minus(retention);

        int deleted = publisherEnabled
                ? outboxRepository.deletePublishedBefore(cutoff, batchSize)
                : outboxRepository.deleteAllBefore(cutoff, batchSize);

        if (deleted > 0) {
            log.info("Outbox temizliği: {} kayıt silindi (sınır: {}, ölçüt: {})",
                    deleted, cutoff, publisherEnabled ? "yayınlanmış" : "yaş");
        }
    }
}
