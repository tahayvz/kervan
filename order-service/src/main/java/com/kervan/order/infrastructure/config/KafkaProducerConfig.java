package com.kervan.order.infrastructure.config;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Bu serviste <b>iki</b> Kafka üreticisi var, çünkü iki farklı iş yapıyorlar.
 *
 * <ul>
 *   <li><b>Outbox üreticisi</b> tabloda duran hazır baytları taşır. Gövdeye hiç
 *       bakmaz; serileştirme çoktan yapılmıştır.</li>
 *   <li><b>Ölü mektup üreticisi</b> çözülmüş bir Avro nesnesini yazar. Onu ham bayt
 *       üreticisiyle göndermek çalışma anında hata verirdi.</li>
 * </ul>
 *
 * <p>İkisi burada açıkça tanımlı. Spring Boot yalnızca <b>tek</b> {@code KafkaTemplate}
 * otomatik kurar ve elle tanımlanmış bir tane görünce geri çekilir; ikisine de ihtiyaç
 * olduğu için ikisi de burada. Enjeksiyon generic tipe göre çözülür.
 */
@Configuration
class KafkaProducerConfig {

    private final String bootstrapServers;

    KafkaProducerConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    KafkaTemplate<String, byte[]> outboxKafkaTemplate() {
        Map<String, Object> props = base();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        // Broker'ların tamamı yazmayı onaylamadan başarı sayma: outbox'ın
        // "gönderildi" işareti ancak gerçekten dayanıklı yazımdan sonra konmalı.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            @Value("${kervan.schema-registry.url}") String schemaRegistryUrl) {

        Map<String, Object> props = base();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    private Map<String, Object> base() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }
}
