package com.kervan.order.saga;

import com.kervan.contracts.inventory.v1.StockReleased;
import com.kervan.contracts.inventory.v1.StockReservationFailed;
import com.kervan.contracts.inventory.v1.StockReserved;
import com.kervan.contracts.payment.v1.PaymentFailed;
import com.kervan.contracts.payment.v1.PaymentProcessed;
import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.security.TestJwtSupport;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga'nın yollarını gerçek Kafka üzerinden yürütür.
 *
 * <h2>Neyi kapsıyor, neyi kapsamıyor</h2>
 * Sipariş servisi gerçekten çalışıyor; stok ve ödeme servislerinin <b>cevapları</b>
 * elle Kafka'ya konuyor. Yani bu test orchestrator'ın kararlarını ve mesajlaşmayı
 * doğrular.
 *
 * <p><b>Üç servisin birlikte çalıştığı bir test yok.</b> Öyle bir test üç Spring
 * uygulamasını aynı anda ayağa kaldırmayı gerektirir. Her servisin kendi uçtan uca
 * testi var ve aralarındaki sözleşme {@code event-contracts} ile sabitli — ama bu,
 * "hepsi birlikte çalışıyor" demenin yerine geçmez.
 */
@DisplayName("Sipariş saga'sı")
class OrderSagaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${kervan.topics.inventory-events}")
    private String inventoryEvents;

    @Value("${kervan.topics.payment-events}")
    private String paymentEvents;

    @Test
    @DisplayName("mutlu yol: stok ayrıldı, ödeme alındı, sipariş onaylandı")
    void happyPath() {
        String orderId = placeOrder();

        // Sipariş alınır alınmaz ilk komut yazılmış olmalı: saga siparişle aynı
        // transaction'da başlar.
        awaitOutbox(orderId, "ReserveStock");
        assertThat(sagaState(orderId)).isEqualTo("STOCK_RESERVING");

        send(inventoryEvents, orderId, StockReserved.newBuilder()
                .setOrderId(orderId).setReservationId("res-1")
                .setReservedAt(Instant.now()).build());

        awaitOutbox(orderId, "ProcessPayment");
        awaitSagaState(orderId, "PAYMENT_PROCESSING");

        send(paymentEvents, orderId, PaymentProcessed.newBuilder()
                .setOrderId(orderId).setPaymentId("pay-1")
                .setAmount(new BigDecimal("249.9000")).setCurrency("TRY")
                .setProcessedAt(Instant.now()).build());

        awaitOutbox(orderId, "OrderConfirmed");
        awaitSagaState(orderId, "COMPLETED");
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("telafi yolu: ödeme başarısız, stok geri bırakıldı, sipariş iptal")
    void compensationPath() {
        String orderId = placeOrder();
        awaitOutbox(orderId, "ReserveStock");

        send(inventoryEvents, orderId, StockReserved.newBuilder()
                .setOrderId(orderId).setReservationId("res-1")
                .setReservedAt(Instant.now()).build());
        awaitOutbox(orderId, "ProcessPayment");

        send(paymentEvents, orderId, PaymentFailed.newBuilder()
                .setOrderId(orderId).setReason("limit asildi")
                .setFailedAt(Instant.now()).build());

        // Önce telafi: sipariş henüz iptal DEĞİL, çünkü stok hâlâ tutuluyor.
        awaitOutbox(orderId, "ReleaseStock");
        awaitSagaState(orderId, "STOCK_RELEASING");
        assertThat(orderStatus(orderId)).isEqualTo("PLACED");

        send(inventoryEvents, orderId, StockReleased.newBuilder()
                .setOrderId(orderId).setReservationId("res-1")
                .setReleasedAt(Instant.now()).build());

        awaitOutbox(orderId, "OrderCancelled");
        awaitSagaState(orderId, "CANCELLED");
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("stok yetmezse telafi adımı olmadan iptal edilir")
    void stockFailureCancelsWithoutCompensation() {
        String orderId = placeOrder();
        awaitOutbox(orderId, "ReserveStock");

        send(inventoryEvents, orderId, StockReservationFailed.newBuilder()
                .setOrderId(orderId).setReason("stok yok")
                .setFailedAt(Instant.now()).build());

        awaitOutbox(orderId, "OrderCancelled");
        awaitSagaState(orderId, "CANCELLED");
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        // Yapılmış bir ayırma yok; geri bırakma komutu gönderilmemeli.
        assertThat(outboxCount(orderId, "ReleaseStock")).isZero();
    }

    @Test
    @DisplayName("aynı olay iki kez gelirse komut ikinci kez gönderilmez")
    void duplicateEventDoesNotResend() {
        String orderId = placeOrder();
        awaitOutbox(orderId, "ReserveStock");

        StockReserved reserved = StockReserved.newBuilder()
                .setOrderId(orderId).setReservationId("res-1")
                .setReservedAt(Instant.now()).build();
        send(inventoryEvents, orderId, reserved);
        awaitOutbox(orderId, "ProcessPayment");
        send(inventoryEvents, orderId, reserved);

        // İkinci işleme saga'yı zaten ilerlemiş bulur; ikinci kez ödeme istenmez.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxCount(orderId, "ProcessPayment")).isEqualTo(1));
    }

    private String placeOrder() {
        PlaceOrderRequest request = new PlaceOrderRequest("TRY", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 2, new BigDecimal("100.00")),
                new PlaceOrderRequest.Line("p-2", "SKU-2", 1, new BigDecimal("49.90"))));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.tokenFor("c-1", "CUSTOMER"));

        return rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), OrderResponse.class).getBody().id();
    }

    private void send(String topic, String key, Object message) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", KafkaAvroSerializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
                "mock://order-service-tests");
        props.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY,
                TopicRecordNameStrategy.class.getName());

        try (Producer<String, Object> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, message));
            producer.flush();
        }
    }

    private void awaitOutbox(String orderId, String eventType) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(outboxCount(orderId, eventType)).isPositive());
    }

    private void awaitSagaState(String orderId, String state) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(sagaState(orderId)).isEqualTo(state));
    }

    private int outboxCount(String orderId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, orderId, eventType);
    }

    private String sagaState(String orderId) {
        return jdbc.queryForObject(
                "SELECT state FROM order_sagas WHERE order_id = ?", String.class, orderId);
    }

    private String orderStatus(String orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?::uuid", String.class, orderId);
    }
}
