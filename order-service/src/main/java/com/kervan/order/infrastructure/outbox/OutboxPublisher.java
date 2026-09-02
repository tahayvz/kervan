package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Outbox tablosundaki kayıtları Kafka'ya taşır.
 *
 * <p>Sipariş yazan transaction burayı beklemez; bu sınıf arka planda periyodik çalışır.
 * Kafka bir süre erişilemez olsa bile sipariş alınmaya devam eder, olaylar tabloda
 * birikir ve bağlantı geri geldiğinde sırayla gönderilir.
 *
 * <p><b>Sıralama:</b> Kayıtlar Kafka'ya {@code aggregateId} anahtarıyla yazılır. Aynı
 * siparişin olayları aynı partition'a düşer, dolayısıyla o sipariş için sıra korunur.
 * Farklı siparişler arasında genel bir sıra garantisi yoktur ve gerekmez.
 *
 * <p><b>Hata durumu:</b> Bir kayıt gönderilemezse işaretlenmez ve bir sonraki turda
 * tekrar denenir. Turda kalan kayıtlara devam edilmez; sıra bozulmasın diye döngü
 * durur.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final String topic;
    private final int batchSize;

    OutboxPublisher(OutboxRepository outboxRepository,
                    KafkaTemplate<String, String> kafkaTemplate,
                    Clock clock,
                    @Value("${kervan.outbox.topic}") String topic,
                    @Value("${kervan.outbox.batch-size}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${kervan.outbox.poll-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> pending = outboxRepository.findUnpublished(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxMessage message : pending) {
            if (!publish(message)) {
                break;
            }
        }
    }

    /** @return gönderim başarılıysa true; false dönerse tur sonlandırılır */
    private boolean publish(OutboxMessage message) {
        try {
            kafkaTemplate.send(topic, message.aggregateId(), message.payload())
                    .get();   // gönderimin gerçekten kabul edildiğini görmeden işaretleme
            outboxRepository.markPublished(message.id(), clock.instant());
            log.debug("Outbox kaydı yayınlandı: id={} type={}", message.id(), message.eventType());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox yayını kesildi: id={}", message.id());
            return false;
        } catch (Exception e) {
            // İşaretlemiyoruz: kayıt tabloda kalır, bir sonraki turda tekrar denenir.
            log.warn("Outbox kaydı yayınlanamadı, tekrar denenecek: id={}", message.id(), e);
            return false;
        }
    }
}
