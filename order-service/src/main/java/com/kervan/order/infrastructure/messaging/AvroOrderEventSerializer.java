package com.kervan.order.infrastructure.messaging;

import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.port.OrderEventSerializer;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Olayı Avro ile serileştirir ve şemasını Schema Registry'ye kaydeder.
 *
 * <h2>Şema ne zaman kaydedilir?</h2>
 * İlk olay serileştirildiğinde. Serileştirici şemayı bir <em>subject</em> adı altında
 * Registry'ye gönderir; Registry ona bir kimlik verir ve bu kimlik mesajın ilk
 * baytlarına yazılır. Sonraki mesajlarda şema tekrar gönderilmez, yalnızca kimlik.
 *
 * <h2>Subject adı neden konu adı değil?</h2>
 * Varsayılan strateji subject'i {@code <konu>-value} yapar: konu başına <b>tek</b>
 * şema. Bu proje aynı konuya birden fazla olay tipi yazar — sipariş konusunda
 * {@code OrderPlaced}, {@code OrderConfirmed}, {@code OrderCancelled} birlikte durur.
 * Varsayılan stratejide ikinci tip, birincinin uyumsuz bir sürümü sayılıp reddedilirdi.
 *
 * <p>{@code TopicRecordNameStrategy} subject'i {@code <konu>-<kayıt tam adı>} yapar:
 * her tipin kendi sürüm geçmişi ve kendi uyumluluk denetimi olur, konu ise ortak kalır.
 * Konunun ortak kalması şart, çünkü sıra yalnızca <b>tek bir konu</b> içinde ve aynı
 * anahtar için korunur. {@code OrderPlaced} ile {@code OrderCancelled} ayrı konularda
 * olsaydı, tüketici iptali oluşturmadan önce görebilirdi (ADR-0009).
 *
 * <h2>Uyumsuz şema ne olur?</h2>
 * Registry, konunun uyumluluk kuralına aykırı bir şemayı reddeder ve serileştirme
 * hata verir. Bu hata sipariş yazan transaction'ın içinde oluşur: sipariş de
 * yazılmaz. Bilerek böyledir — okunamayacak bir olay üretmektense siparişi
 * reddetmek doğrudur, çünkü aksi hâlde sipariş var olur ama onu duyan olmaz.
 *
 * <h2>Neden kayıt otomatik?</h2>
 * {@code auto.register.schemas} geliştirme kolaylığı sağlar ama üretimde
 * kapatılmalıdır: orada şemayı uygulama değil, denetimden geçmiş bir yayın adımı
 * kaydeder. Ayar dışarıdan verilebilir bırakıldı.
 */
@Component
class AvroOrderEventSerializer implements OrderEventSerializer {

    private final KafkaAvroSerializer serializer;
    private final String topic;

    AvroOrderEventSerializer(@Value("${kervan.outbox.topic}") String topic,
                             @Value("${kervan.schema-registry.url}") String schemaRegistryUrl,
                             @Value("${kervan.schema-registry.auto-register:true}") boolean autoRegister) {
        this.topic = topic;
        this.serializer = new KafkaAvroSerializer();
        this.serializer.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegister,
                AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName()
        ), false); // false = değer serileştiricisi (anahtar değil)
    }

    @Override
    public byte[] serialize(OrderPlaced event) {
        return serializer.serialize(topic, OrderPlacedAvroMapper.toAvro(event));
    }

    @PreDestroy
    void close() {
        serializer.close();
    }
}
