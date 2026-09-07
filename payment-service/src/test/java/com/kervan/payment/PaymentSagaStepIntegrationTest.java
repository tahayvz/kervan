package com.kervan.payment;

import com.kervan.contracts.payment.v1.PaymentFailed;
import com.kervan.contracts.payment.v1.PaymentProcessed;
import com.kervan.contracts.payment.v1.ProcessPayment;
import com.kervan.contracts.payment.v1.RefundPayment;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga'nın ödeme adımını uçtan uca doğrular: komut Kafka'dan gelir, tahsilat yapılır,
 * cevap outbox'a yazılır.
 *
 * <p>Servis içeriden çağrılmıyor; mesaj gerçekten Kafka'ya konuyor ve dinleyici onu
 * kendi alıyor.
 */
@DisplayName("Saga ödeme adımı")
class PaymentSagaStepIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${kervan.payment.commands-topic}")
    private String commandsTopic;

    @Value("${kervan.payment.events-topic}")
    private String eventsTopic;

    @Value("${kervan.payment.simulator.decline-above}")
    private BigDecimal declineAbove;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM outbox_messages");
        jdbc.update("DELETE FROM payments");
    }

    @Test
    @DisplayName("tutar sınırın altındaysa tahsil edilir ve PaymentProcessed yazılır")
    void capturesAndAnswers() {
        String orderId = UUID.randomUUID().toString();

        send(processPayment(orderId, new BigDecimal("249.9000")));

        Object event = awaitOutboxEvent(orderId, "PaymentProcessed");
        assertThat(event).isInstanceOf(PaymentProcessed.class);
        assertThat(((PaymentProcessed) event).getOrderId()).isEqualTo(orderId);
        assertThat(paymentStatusOf(orderId)).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("sağlayıcı reddederse PaymentFailed yazılır ve kayıt oluşmaz")
    void answersWithFailureWhenDeclined() {
        String orderId = UUID.randomUUID().toString();

        // Taklit sağlayıcı sınırın üstünü reddeder — kural tabanlı, rastgele değil.
        send(processPayment(orderId, declineAbove.add(new BigDecimal("1.0000"))));

        Object event = awaitOutboxEvent(orderId, "PaymentFailed");
        assertThat(event).isInstanceOf(PaymentFailed.class);
        assertThat(countPayments(orderId)).isZero();
    }

    @Test
    @DisplayName("telafi komutu ödemeyi iade eder")
    void refundReturnsMoney() {
        String orderId = UUID.randomUUID().toString();

        send(processPayment(orderId, new BigDecimal("100.0000")));
        awaitOutboxEvent(orderId, "PaymentProcessed");

        send(RefundPayment.newBuilder()
                .setOrderId(orderId)
                .setPaymentId("yok-sayilir")
                .setAmount(new BigDecimal("100.0000"))
                .setCurrency("TRY")
                .setRequestedAt(Instant.now())
                .build());

        awaitOutboxEvent(orderId, "PaymentRefunded");
        assertThat(paymentStatusOf(orderId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("aynı komut iki kez gelirse ikinci kez tahsilat yapılmaz")
    void duplicateCommandDoesNotChargeTwice() {
        String orderId = UUID.randomUUID().toString();

        ProcessPayment command = processPayment(orderId, new BigDecimal("100.0000"));
        send(command);
        awaitOutboxEvent(orderId, "PaymentProcessed");
        send(command);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(countPayments(orderId)).isEqualTo(1);
            assertThat(countEvents(orderId)).isEqualTo(1);
        });
    }

    private ProcessPayment processPayment(String orderId, BigDecimal amount) {
        return ProcessPayment.newBuilder()
                .setOrderId(orderId)
                .setCustomerId("c-1")
                .setAmount(amount)
                .setCurrency("TRY")
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
        return command instanceof ProcessPayment process
                ? process.getOrderId()
                : ((RefundPayment) command).getOrderId();
    }

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

    private int countPayments(String orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId);
    }

    private String paymentStatusOf(String orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }
}
