package com.kervan.order.infrastructure.config;

import com.kervan.order.infrastructure.messaging.UnsupportedSagaEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Sürekli hata veren bir mesajın kuyruğu kilitlemesini engeller.
 *
 * <h2>Sorun</h2>
 * Bir dinleyici istisna fırlatırsa offset ilerlemez ve aynı mesaj tekrar gelir. Hata
 * kalıcıysa bu sonsuza kadar sürer ve o partition'daki <b>arkadaki bütün mesajlar</b>
 * bekler. Saga bekleyen siparişlerle birlikte durur.
 *
 * <p>Bu teorik bir risk değil: idempotentlik denetimi kaldırıldığında tekrar gelen bir
 * komut veritabanı kısıtına takılıyor ve tam olarak bu döngü oluşuyor. Testte
 * gözlendi.
 *
 * <h2>Karar</h2>
 * Birkaç kez, aralarını açarak yeniden denenir; hâlâ başarısızsa mesaj
 * {@code <konu>.DLT} konusuna taşınır ve akış devam eder.
 *
 * <p><b>Ödün:</b> Geçici bir arıza (veritabanı birkaç dakika erişilemez) yeniden deneme
 * penceresinden uzun sürerse mesaj DLT'ye düşer ve otomatik işlenmez; oradan elle geri
 * konması gerekir. Alternatifi, kalıcı bir hatada kuyruğu süresiz kilitlemekti.
 * Bekleyen siparişlerin tamamını durdurmaktansa tek bir mesajı kenara almak tercih
 * edildi — DLT boş kalmadığı sürece izlenmelidir.
 */
@Configuration
class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate,
            @Value("${kervan.kafka.retry.initial-interval-ms}") long initialInterval,
            @Value("${kervan.kafka.retry.max-interval-ms}") long maxInterval,
            @Value("${kervan.kafka.retry.max-elapsed-ms}") long maxElapsed) {

        // Artan aralık: ilk denemeler hızlı (anlık bir sıkışıklık geçebilir), sonrakiler
        // seyrek (erişilemeyen bir bağımlılığı saniyede bir dövmenin faydası yok).
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval, 2.0);
        backOff.setMaxInterval(maxInterval);
        backOff.setMaxElapsedTime(maxElapsed);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic(record), -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Bazı hatalar beklemekle düzelmez. Tanınmayan bir olay tipi, yeniden
        // denendiğinde de tanınmaz; denemek yalnızca partition'ı geciktirir. Bu tür
        // hatalar doğrudan DLT'ye gider.
        handler.addNotRetryableExceptions(UnsupportedSagaEventException.class);

        return handler;
    }

    /** Partition numarası taşınmaz (-1): DLT'nin kaynak konuyla aynı bölümlemesi olmayabilir. */
    private static String deadLetterTopic(ConsumerRecord<?, ?> record) {
        return record.topic() + ".DLT";
    }
}
