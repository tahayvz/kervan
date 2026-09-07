package com.kervan.order.infrastructure.messaging;

import com.kervan.order.domain.event.OrderPlaced;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Avro serileştiricinin ürettiği baytların gerçekten çözülebildiğini doğrular.
 *
 * <p>Sahte kayıt defteri ({@code mock://}) kullanılır: şema kaydı, kimlik atama ve
 * çözümleme gerçek kodla yürür, yalnızca ağ katmanı devre dışıdır. Böylece bu testler
 * container başlatmadan saniyeler içinde çalışır.
 */
@DisplayName("AvroOrderEventSerializer")
class AvroOrderEventSerializerTest {

    private static final String SCOPE = "avro-serializer-test";
    private static final String REGISTRY_URL = "mock://" + SCOPE;
    private static final String TOPIC = "kervan.orders.events";
    private static final Instant PLACED_AT = Instant.parse("2026-03-01T10:15:30Z");

    private AvroOrderEventSerializer serializer;
    private KafkaAvroDeserializer deserializer;

    @BeforeEach
    void setUp() {
        serializer = new AvroOrderEventSerializer(TOPIC, REGISTRY_URL, true);
        deserializer = new KafkaAvroDeserializer();
        deserializer.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL,
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true"
        ), false);
    }

    @AfterEach
    void tearDown() {
        deserializer.close();
        // Sahte kayıt defteri JVM ömrü boyunca yaşar; testler birbirinin
        // kaydettiği şemayı görmesin diye her testten sonra temizlenir.
        MockSchemaRegistry.dropScope(SCOPE);
    }

    @Test
    @DisplayName("olay serileştirilip aynı değerlerle geri okunur")
    void serialisedEventIsReadableBack() {
        byte[] bytes = serializer.serialize(event());

        com.kervan.contracts.order.v1.OrderPlaced decoded =
                (com.kervan.contracts.order.v1.OrderPlaced) deserializer.deserialize(TOPIC, bytes);

        assertThat(decoded.getOrderId()).isEqualTo("order-1");
        assertThat(decoded.getCustomerId()).isEqualTo("c-1");
        assertThat(decoded.getCurrency()).isEqualTo("TRY");
        assertThat(decoded.getTotalAmount()).isEqualByComparingTo("249.90");
        assertThat(decoded.getPlacedAt()).isEqualTo(PLACED_AT);
        assertThat(decoded.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getSku()).isEqualTo("SKU-1");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getUnitPrice()).isEqualByComparingTo("124.95");
        });
    }

    @Test
    @DisplayName("baytların başında sihirli bayt ve şema kimliği taşınır")
    void writesConfluentWireFormatHeader() {
        byte[] bytes = serializer.serialize(event());

        // Confluent kablo biçimi: [0x00][4 baytlık şema kimliği][Avro gövde].
        // Şemanın kendisi her mesajda tekrar gönderilmez; yalnızca kimliği.
        assertThat(bytes[0]).isZero();

        int schemaId = ByteBuffer.wrap(bytes, 1, 4).getInt();
        assertThat(schemaId).isPositive();
    }

    @Test
    @DisplayName("şema, konu ve kayıt adından türetilen subject altına kaydedilir")
    void registersSchemaUnderTopicRecordNameSubject() throws Exception {
        serializer.serialize(event());

        SchemaRegistryClient client = MockSchemaRegistry.getClientForScope(SCOPE);

        // Varsayılan strateji <topic>-value olurdu: konu başına tek şema. Bu proje
        // aynı konuya birden çok olay tipi yazar (OrderPlaced, OrderConfirmed,
        // OrderCancelled), o yüzden subject kayıt adını da içerir. Aksi hâlde ikinci
        // tip, birincinin uyumsuz bir sürümü sayılıp reddedilirdi.
        assertThat(client.getAllSubjects())
                .containsExactly(TOPIC + "-com.kervan.contracts.order.v1.OrderPlaced");
    }

    @Test
    @DisplayName("3 ondalıklı para birimi kayıpsız serileştirilir")
    void serialisesThreeDecimalCurrency() {
        // KWD'nin ondalık hane sayısı 3; Money tutarı ölçek 3 ile tutar. Şema ölçeği
        // 2 olsaydı bu olay serileştirilemez, sipariş 500 ile reddedilirdi.
        OrderPlaced kuwaitiOrder = new OrderPlaced(
                "order-1", "c-1", new BigDecimal("10.555"), "KWD",
                List.of(new OrderPlaced.Item("p-1", "SKU-1", 1, new BigDecimal("10.555"))),
                PLACED_AT);

        com.kervan.contracts.order.v1.OrderPlaced decoded =
                (com.kervan.contracts.order.v1.OrderPlaced)
                        deserializer.deserialize(TOPIC, serializer.serialize(kuwaitiOrder));

        assertThat(decoded.getCurrency()).isEqualTo("KWD");
        assertThat(decoded.getTotalAmount()).isEqualByComparingTo("10.555");
    }

    @Test
    @DisplayName("şemaya sığmayan hassasiyet sessizce yuvarlanmaz")
    void rejectsAmountWithMorePrecisionThanSchema() {
        // Şema ölçeği 4. Beşinci basamakta bir değer varsa bu, olayın Money
        // kullanılmadan üretildiği anlamına gelir — programlama hatası. Sessizce
        // yuvarlayıp yanlış tutar yayınlamaktansa burada durmak doğrudur.
        OrderPlaced broken = new OrderPlaced(
                "order-1", "c-1", new BigDecimal("249.90501"), "TRY",
                List.of(new OrderPlaced.Item("p-1", "SKU-1", 2, new BigDecimal("124.95"))),
                PLACED_AT);

        assertThatThrownBy(() -> serializer.serialize(broken))
                .isInstanceOf(ArithmeticException.class);
    }

    private static OrderPlaced event() {
        return new OrderPlaced(
                "order-1",
                "c-1",
                new BigDecimal("249.90"),
                "TRY",
                List.of(new OrderPlaced.Item("p-1", "SKU-1", 2, new BigDecimal("124.95"))),
                PLACED_AT);
    }
}
