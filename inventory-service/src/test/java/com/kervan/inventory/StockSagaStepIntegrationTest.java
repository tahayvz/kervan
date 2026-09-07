package com.kervan.inventory;

import com.kervan.contracts.inventory.v1.ReleaseStock;
import com.kervan.contracts.inventory.v1.ReservationItem;
import com.kervan.contracts.inventory.v1.ReserveStock;
import com.kervan.contracts.inventory.v1.StockReservationFailed;
import com.kervan.contracts.inventory.v1.StockReserved;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga'nın stok adımını uçtan uca doğrular: komut Kafka'dan gelir, stok değişir, cevap
 * outbox'a yazılır.
 *
 * <p>Servis içeriden çağrılmıyor — mesaj gerçekten Kafka'ya konuyor ve dinleyici onu
 * kendi alıyor. Konuda birden fazla komut tipi olduğu için tipe göre yönlendirmenin
 * (@KafkaHandler) çalıştığı da böylece doğrulanmış oluyor.
 */
@DisplayName("Saga stok adımı")
class StockSagaStepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${kervan.inventory.commands-topic}")
    private String commandsTopic;

    @Value("${kervan.inventory.events-topic}")
    private String eventsTopic;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM outbox_messages");
        jdbc.update("DELETE FROM reservation_lines");
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM stock_items");
    }

    private void stockOnHand(String sku, int available) {
        jdbc.update("""
                INSERT INTO stock_items (sku, available_quantity, reserved_quantity, updated_at)
                VALUES (?, ?, 0, now())
                """, sku, available);
    }

    @Test
    @DisplayName("yeterli stok varsa ayrılır ve StockReserved yazılır")
    void reservesStockAndAnswers() {
        String orderId = UUID.randomUUID().toString();
        stockOnHand("SKU-1", 10);

        send(reserveStock(orderId, "SKU-1", 3));

        Object event = awaitOutboxEvent(orderId, "StockReserved");
        assertThat(event).isInstanceOf(StockReserved.class);
        assertThat(((StockReserved) event).getOrderId()).isEqualTo(orderId);

        assertThat(availableOf("SKU-1")).isEqualTo(7);
        assertThat(reservedOf("SKU-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("stok yetmezse StockReservationFailed yazılır ve stok değişmez")
    void answersWithFailureWhenStockIsShort() {
        String orderId = UUID.randomUUID().toString();
        stockOnHand("SKU-1", 1);

        send(reserveStock(orderId, "SKU-1", 5));

        Object event = awaitOutboxEvent(orderId, "StockReservationFailed");
        assertThat(event).isInstanceOf(StockReservationFailed.class);

        // Cevap yazıldı ama stok hiç dokunulmadı: ikisi aynı commit'te.
        assertThat(availableOf("SKU-1")).isEqualTo(1);
        assertThat(reservedOf("SKU-1")).isZero();
    }

    @Test
    @DisplayName("telafi komutu tutulan stoğu geri verir")
    void releaseReturnsStock() {
        String orderId = UUID.randomUUID().toString();
        stockOnHand("SKU-1", 10);

        send(reserveStock(orderId, "SKU-1", 4));
        awaitOutboxEvent(orderId, "StockReserved");

        send(ReleaseStock.newBuilder()
                .setOrderId(orderId)
                .setReservationId("yok-sayilir")
                .setRequestedAt(Instant.now())
                .build());

        awaitOutboxEvent(orderId, "StockReleased");
        assertThat(availableOf("SKU-1")).isEqualTo(10);
        assertThat(reservedOf("SKU-1")).isZero();
    }

    @Test
    @DisplayName("aynı komut iki kez gelirse stok iki kez düşmez")
    void duplicateCommandDoesNotReserveTwice() {
        String orderId = UUID.randomUUID().toString();
        stockOnHand("SKU-1", 10);

        ReserveStock command = reserveStock(orderId, "SKU-1", 3);
        send(command);
        awaitOutboxEvent(orderId, "StockReserved");
        send(command);

        // İkinci komutun işlenmesini bekle, sonra durumu kontrol et. Beklemeden
        // bakmak testi anlamsız kılardı: henüz işlenmemiş bir mesajın etkisi zaten
        // görünmez.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(availableOf("SKU-1")).isEqualTo(7);
            assertThat(countEvents(orderId)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("tanınmayan komut kaybolmaz, ölü mektup konusuna gider")
    void unknownCommandGoesToTheDeadLetterTopic() {
        String orderId = UUID.randomUUID().toString();

        // Bu konuya ait olmayan bir tip: yeni bir komut tipi eklendiğinde henüz
        // güncellenmemiş bir kopyanın göreceği şeyin aynısı.
        send(com.kervan.contracts.order.v1.OrderConfirmed.newBuilder()
                .setOrderId(orderId)
                .setConfirmedAt(Instant.now())
                .build());

        try (KafkaConsumer<String, Object> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(commandsTopic + ".DLT"));

            ConsumerRecord<String, Object> record = await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> pollDeadLetter(consumer, orderId), r -> r != null);

            // Sessizce atlansaydı saga cevap beklerken asılı kalır ve kimse görmezdi.
            assertThat(record.value()).isInstanceOf(
                    com.kervan.contracts.order.v1.OrderConfirmed.class);
        }
    }

    private ConsumerRecord<String, Object> pollDeadLetter(
            KafkaConsumer<String, Object> consumer, String orderId) {
        for (ConsumerRecord<String, Object> record : consumer.poll(Duration.ofMillis(500))) {
            if (orderId.equals(record.key())) {
                return record;
            }
        }
        return null;
    }

    private KafkaConsumer<String, Object> deadLetterConsumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "dlt-test-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer",
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        props.put("value.deserializer", KafkaAvroDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        props.put("specific.avro.reader", "true");
        return new KafkaConsumer<>(props);
    }

    private ReserveStock reserveStock(String orderId, String sku, int quantity) {
        return ReserveStock.newBuilder()
                .setOrderId(orderId)
                .setItems(List.of(ReservationItem.newBuilder()
                        .setProductId("p-1").setSku(sku).setQuantity(quantity).build()))
                .setRequestedAt(Instant.now())
                .build();
    }

    private void send(Object command) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", KafkaAvroSerializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        props.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName());

        try (Producer<String, Object> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(commandsTopic, orderIdOf(command), command));
            producer.flush();
        }
    }

    private static String orderIdOf(Object command) {
        if (command instanceof ReserveStock reserve) {
            return reserve.getOrderId();
        }
        if (command instanceof ReleaseStock release) {
            return release.getOrderId();
        }
        return ((com.kervan.contracts.order.v1.OrderConfirmed) command).getOrderId();
    }

    /** Outbox'a düşen olayı bekler ve Avro'dan çözer. */
    private Object awaitOutboxEvent(String orderId, String eventType) {
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> readOutboxPayload(orderId, eventType), payload -> payload != null);
    }

    private Object readOutboxPayload(String orderId, String eventType) {
        List<byte[]> rows = jdbc.query(
                "SELECT payload FROM outbox_messages WHERE aggregate_id = ? AND event_type = ?",
                (rs, i) -> rs.getBytes("payload"), orderId, eventType);
        if (rows.isEmpty()) {
            return null;
        }
        try (KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer()) {
            deserializer.configure(Map.of(
                    AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL,
                    "specific.avro.reader", "true"), false);
            return deserializer.deserialize(eventsTopic, rows.get(0));
        }
    }

    private int countEvents(String orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE aggregate_id = ?", Integer.class, orderId);
    }

    private int availableOf(String sku) {
        return jdbc.queryForObject(
                "SELECT available_quantity FROM stock_items WHERE sku = ?", Integer.class, sku);
    }

    private int reservedOf(String sku) {
        return jdbc.queryForObject(
                "SELECT reserved_quantity FROM stock_items WHERE sku = ?", Integer.class, sku);
    }
}
