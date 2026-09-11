package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import com.kervan.order.infrastructure.observability.TraceParentProvider;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox tablosundaki kayıtları Kafka'ya taşır.
 *
 * <p>Sipariş yazan transaction burayı beklemez; bu sınıf arka planda periyodik çalışır.
 * Kafka bir süre erişilemez olsa bile sipariş alınmaya devam eder, olaylar tabloda
 * birikir ve bağlantı geri geldiğinde gönderilir.
 *
 * <h2>Aynı olayın iki kez gitmemesi</h2>
 * Kayıtlar {@code FOR UPDATE SKIP LOCKED} ile kilitlenerek okunur. Servisin birden
 * fazla kopyası çalıştığında her kopya farklı satırları alır; aynı olay iki kez
 * yayınlanmaz.
 *
 * <h2>Tıkanmama</h2>
 * Gönderilemeyen bir kaydın deneme sayacı artar. Sayaç sınıra ulaşınca kayıt sorgunun
 * dışında kalır: kuyruk akmaya devam eder, sorunlu kayıt incelenmek üzere tabloda
 * durur. Aksi hâlde tek bir bozuk mesaj (örneğin broker sınırını aşan bir payload)
 * arkasındaki tüm olayları süresiz bloklardı.
 *
 * <h2>Transaction süresi</h2>
 * Gönderim {@code sendTimeout} ile sınırlıdır. Zaman aşımı olmadan {@code get()}
 * çağırmak, broker erişilemezken transaction'ı ve veritabanı bağlantısını dakikalarca
 * açık tutardı; bu, Kafka kesintisini sipariş alma yoluna bulaştırırdı — outbox'ın
 * önlemek için var olduğu şeyin ta kendisi.
 *
 * <p>Her kayıt <b>kendi hedefine</b> gönderilir: bu servis yalnızca kendi olaylarını
 * değil, saga'nın diğer servislere gönderdiği komutları da outbox'a yazar. Hepsi tek
 * konuya gitseydi komutlar yanlış yere düşerdi.
 *
 * <p>Sıralama: Kafka'ya {@code aggregateId} anahtarıyla yazılır, aynı siparişin
 * olayları aynı partition'a düşer. Bir gönderim başarısız olduğunda tur sonlandırılır
 * ki o siparişin sonraki olayları öne geçmesin.
 *
 * <p>Mesaj gövdesi Avro ile serileştirilmiş baytlardır; ilk beş bayt şemanın
 * Schema Registry'deki kimliğini taşır (ADR-0008). Bu sınıf gövdenin içeriğine
 * hiç bakmaz, olduğu gibi taşır.
 *
 * <h2>Debezium devredeyken kapatilir</h2>
 * Ayni isi Debezium (CDC) de yapabilir: veritabaninin degisiklik gunlugunu okuyup
 * ayni konuya yazar. Ikisi birden acik olursa her olay iki kez gider. Bu yuzden
 * sinif {@code kervan.outbox.publisher.enabled} ayarina baglidir; Debezium
 * kullanilan ortamda bu ayar {@code false} yapilir. Karsilastirma:
 * {@code infra/docker/debezium/README.md}.
 *
 * <h2>İzleme bağlamı</h2>
 * Her kayıt, onu YAZAN isteğin izleme kimliğiyle gönderilir; bu yayıncının kendi
 * izleme bağlamıyla değil. Yayıncı zamanlanmış bir iştir, siparişi alan istekle
 * arasında bağ yoktur. Aynı sebeple bu şablonda Spring'in otomatik gözlemi
 * kapalıdır ({@code KafkaProducerConfig}): açık olsaydı kütüphane başlığı kendi
 * yanlış bağlamıyla ezerdi. Debezium ile bu sınıf aynı başlığı üretir; hangi yol
 * kullanılırsa kullanılsın tüketicinin gördüğü şey aynıdır (ADR-0013).
 *
 * <p>Teslimat <b>en az bir kez</b>'dir: işaretleme öncesi çökme aynı olayı tekrar
 * gönderir. Tüketiciler idempotent olmalıdır ({@link OutboxMessage}).
 */
@Component
@ConditionalOnProperty(name = "kervan.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;

    OutboxPublisher(OutboxRepository outboxRepository,
                    KafkaTemplate<String, byte[]> kafkaTemplate,
                    Clock clock,
                    @Value("${kervan.outbox.batch-size}") int batchSize,
                    @Value("${kervan.outbox.max-attempts}") int maxAttempts,
                    @Value("${kervan.outbox.send-timeout}") Duration sendTimeout) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${kervan.outbox.poll-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> deliverable = outboxRepository.lockDeliverable(batchSize, maxAttempts);

        for (OutboxMessage message : deliverable) {
            if (!publish(message)) {
                break;
            }
        }
    }

    /** @return gönderim başarılıysa true; false dönerse bu tur sonlandırılır */
    private boolean publish(OutboxMessage message) {
        try {
            kafkaTemplate.send(toRecord(message))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

            outboxRepository.markPublished(message.id(), clock.instant());
            log.debug("Outbox kaydı yayınlandı: id={} type={}", message.id(), message.eventType());
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox yayını kesildi: id={}", message.id());
            return false;

        } catch (TimeoutException e) {
            recordFailure(message, "Gönderim " + sendTimeout.toMillis() + " ms içinde tamamlanmadı");
            return false;

        } catch (Exception e) {
            recordFailure(message, e.getMessage());
            return false;
        }
    }

    /** Satırda iz varsa W3C başlığı olarak eklenir; yoksa tüketici yeni iz başlatır. */
    private ProducerRecord<String, byte[]> toRecord(OutboxMessage message) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                message.destination(), message.aggregateId(), message.payload());

        if (message.traceParent() != null) {
            record.headers().add(TraceParentProvider.HEADER,
                    message.traceParent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private void recordFailure(OutboxMessage message, String reason) {
        outboxRepository.recordFailedAttempt(message.id(), clock.instant(), reason);
        log.warn("Outbox kaydı yayınlanamadı (sınır: {} deneme): id={} sebep={}",
                maxAttempts, message.id(), reason);
    }
}
