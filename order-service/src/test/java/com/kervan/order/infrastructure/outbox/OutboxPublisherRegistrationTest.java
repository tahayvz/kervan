package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.port.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Uygulama içi yayıncının açılıp kapanabildiğini doğrular.
 *
 * <p><b>Neden önemli?</b> Aynı işi Debezium de yapar: veritabanının değişiklik
 * günlüğünü okuyup aynı Kafka konusuna yazar. İkisi birden çalışırsa her olay iki
 * kez gider. Ayarın gerçekten bean'i kaldırdığı burada sabitlenir; aksi hâlde
 * "kapattım" sanılıp çift yayın yapılırdı ve bu ancak canlıda fark edilirdi.
 */
@DisplayName("OutboxPublisher kaydı")
class OutboxPublisherRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Bu koşucu çıplak bir Spring bağlamı kurar; Boot'un "5s" gibi metinleri
            // Duration'a çeviren dönüştürücüsü kendiliğinden gelmez. Gerçek uygulamada
            // Boot onu kurar, burada elle ekleniyor.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues(
                    "kervan.outbox.batch-size=100",
                    "kervan.outbox.max-attempts=5",
                    "kervan.outbox.send-timeout=5s")
            .withUserConfiguration(OutboxPublisher.class);

    @Test
    @DisplayName("ayar verilmezse yayıncı çalışır")
    void isRegisteredByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(OutboxPublisher.class));
    }

    @Test
    @DisplayName("ayar true ise yayıncı çalışır")
    void isRegisteredWhenEnabled() {
        runner.withPropertyValues("kervan.outbox.publisher.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(OutboxPublisher.class));
    }

    @Test
    @DisplayName("ayar false ise yayıncı hiç oluşturulmaz")
    void isNotRegisteredWhenDisabled() {
        runner.withPropertyValues("kervan.outbox.publisher.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxPublisher.class));
    }
}
