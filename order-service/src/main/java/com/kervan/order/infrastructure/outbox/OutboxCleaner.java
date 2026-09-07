package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <h2>Neden parça parça, ama tek parti değil</h2>
 * Sınırsız tek bir {@code DELETE}, tablo büyümüşse milyonlarca satırı tek
 * transaction'da siler ve sipariş yazan istekler o süre boyunca bekler. Bu yüzden
 * silme partilere bölünür ve <b>her parti kendi transaction'ında</b> çalışır.
 *
 * <p>Ama turda tek parti silmek de yetmez. Parti 1000, tur aralığı bir saat olsaydı
 * temizlik hızı saatte 1000 satırda kalırdı; sipariş hızı bunu geçtiği anda tablo
 * büyümeye devam eder ve hiçbir şey uyarmaz — log "sildim" der, iş çalışıyor görünür.
 * Bu yüzden bir tur, silinecek bir şey kalmayana kadar sürer.
 *
 * <p>Turun bir üst sınırı var ({@code max-batches-per-run}): sonsuza kadar süren bir
 * temizlik de istenmez. Sınıra takılmak "temizlik yetişemiyor" demektir ve
 * <b>uyarı</b> olarak loglanır; görülmesi gereken tek sinyal odur.
 */
@Component
@ConditionalOnProperty(name = "kervan.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final OutboxRepository outboxRepository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final boolean publisherEnabled;

    OutboxCleaner(OutboxRepository outboxRepository,
                  Clock clock,
                  @Value("${kervan.outbox.cleanup.retention}") Duration retention,
                  @Value("${kervan.outbox.cleanup.batch-size}") int batchSize,
                  @Value("${kervan.outbox.cleanup.max-batches-per-run}") int maxBatchesPerRun,
                  @Value("${kervan.outbox.publisher.enabled:true}") boolean publisherEnabled) {
        this.outboxRepository = outboxRepository;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.publisherEnabled = publisherEnabled;
    }

    /**
     * Burada bilerek {@code @Transactional} <b>yok</b>. Olsaydı turdaki bütün partiler
     * tek transaction'da birleşir ve partilere bölmenin tüm anlamı kaybolurdu. Her
     * partinin transaction'ını adaptör açar.
     */
    @Scheduled(fixedDelayString = "${kervan.outbox.cleanup.interval-ms}")
    public void deleteExpired() {
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;

        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int deleted = deleteBatch(cutoff);
            total += deleted;

            // Parti dolmadıysa silinecek kayıt kalmamıştır; bir sonraki sorgu
            // boşuna çalışırdı.
            if (deleted < batchSize) {
                logDeleted(total, cutoff);
                return;
            }
        }

        logDeleted(total, cutoff);
        log.warn("Outbox temizliği tur sınırına ({} parti) takıldı: silinecek kayıt "
                        + "kaldı. Temizlik, kayıtların birikme hızına yetişemiyor olabilir; "
                        + "parti boyutunu ya da tur sıklığını artırmayı değerlendirin.",
                maxBatchesPerRun);
    }

    private int deleteBatch(Instant cutoff) {
        return publisherEnabled
                ? outboxRepository.deletePublishedBefore(cutoff, batchSize)
                : outboxRepository.deleteAllBefore(cutoff, batchSize);
    }

    private void logDeleted(int total, Instant cutoff) {
        if (total > 0) {
            log.info("Outbox temizliği: {} kayıt silindi (sınır: {}, ölçüt: {})",
                    total, cutoff, publisherEnabled ? "yayınlanmış" : "yaş");
        }
    }
}
