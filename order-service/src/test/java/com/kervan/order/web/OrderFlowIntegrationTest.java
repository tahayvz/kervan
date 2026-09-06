package com.kervan.order.web;

import com.kervan.contracts.order.v1.OrderPlaced;
import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.security.TestJwtSupport;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Sipariş akışı: HTTP -> PostgreSQL -> outbox -> Kafka")
class OrderFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OutboxRepository outboxRepository;

    @Value("${kervan.outbox.topic}")
    private String topic;

    /** Her test kendi müşterisiyle çalışır; testler birbirinin verisini görmez. */
    private final String customerId = "customer-" + UUID.randomUUID();

    private String customerToken() {
        return TestJwtSupport.tokenFor(customerId, "CUSTOMER");
    }

    private <T> HttpEntity<T> authed(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken());
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authed() {
        return authed(null);
    }

    private PlaceOrderRequest request() {
        return new PlaceOrderRequest("TRY", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 2, new BigDecimal("100.00")),
                new PlaceOrderRequest.Line("p-2", "SKU-2", 1, new BigDecimal("49.90"))));
    }

    @Test
    void placeOrder_shouldPersistOrderAndComputeTotal() {
        ResponseEntity<OrderResponse> response =
                rest.exchange("/api/v1/orders", HttpMethod.POST, authed(request()), OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotBlank();
        assertThat(response.getBody().totalAmount()).isEqualByComparingTo("249.90");
        assertThat(response.getBody().status().name()).isEqualTo("PLACED");
    }

    @Test
    void placedOrder_shouldBeReadableById() {
        String id = rest.exchange("/api/v1/orders", HttpMethod.POST, authed(request()), OrderResponse.class)
                .getBody().id();

        ResponseEntity<OrderResponse> found =
                rest.exchange("/api/v1/orders/" + id, HttpMethod.GET, authed(), OrderResponse.class);

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().id()).isEqualTo(id);
        assertThat(found.getBody().lines()).hasSize(2);
    }

    @Test
    @DisplayName("olay outbox üzerinden Kafka'ya ulaşır ve yayınlandı olarak işaretlenir")
    void placedOrder_shouldReachKafkaThroughOutbox() {
        String id = rest.exchange("/api/v1/orders", HttpMethod.POST, authed(request()), OrderResponse.class)
                .getBody().id();

        try (KafkaConsumer<String, OrderPlaced> consumer = consumer()) {
            consumer.subscribe(List.of(topic));

            ConsumerRecord<String, OrderPlaced> record = await()
                    .atMost(30, TimeUnit.SECONDS)
                    .until(() -> pollFor(consumer, id), r -> r != null);

            // Anahtar sipariş kimliği: aynı siparişin olayları aynı partition'a düşer.
            assertThat(record.key()).isEqualTo(id);

            // Tüketici mesajı, üreticinin hiç göndermediği bir şemayla çözdü:
            // şemanın kimliği mesajın ilk baytlarındaydı, şemanın kendisi
            // Schema Registry'den geldi (ADR-0008).
            OrderPlaced event = record.value();
            assertThat(event.getOrderId()).isEqualTo(id);
            assertThat(event.getCurrency()).isEqualTo("TRY");
            assertThat(event.getTotalAmount()).isEqualByComparingTo("249.90");
            assertThat(event.getItems()).hasSize(2);
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(outboxRepository.findUnpublished(100))
                        .noneMatch(message -> message.aggregateId().equals(id)));
    }

    @Test
    void unknownOrder_shouldReturnProblemDetail() {
        ResponseEntity<String> response =
                rest.exchange("/api/v1/orders/" + UUID.randomUUID(), HttpMethod.GET, authed(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Sipariş bulunamadı");
    }

    @Test
    void invalidRequest_shouldBeRejectedBeforePersisting() {
        PlaceOrderRequest invalid = new PlaceOrderRequest("TRY", List.of());

        ResponseEntity<String> response =
                rest.exchange("/api/v1/orders", HttpMethod.POST, authed(invalid), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Doğrulama hatası");
    }

    @Test
    void unknownCurrency_shouldBeRejected() {
        PlaceOrderRequest invalid = new PlaceOrderRequest("XXXX", List.of(
                new PlaceOrderRequest.Line("p-1", "SKU-1", 1, new BigDecimal("10.00"))));

        assertThat(rest.exchange("/api/v1/orders", HttpMethod.POST, authed(invalid), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ConsumerRecord<String, OrderPlaced> pollFor(KafkaConsumer<String, OrderPlaced> consumer,
                                                        String key) {
        ConsumerRecords<String, OrderPlaced> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, OrderPlaced> record : records) {
            if (key.equals(record.key())) {
                return record;
            }
        }
        return null;
    }

    /**
     * Olayı Avro ile çözen tüketici. Şema adresi uygulamanınkiyle aynı sahte
     * kayıt defterini gösterir; aynı JVM içinde aynı şema kimlikleri geçerlidir.
     */
    private KafkaConsumer<String, OrderPlaced> consumer() {
        Properties props = new Properties();
        props.putAll(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", KafkaAvroDeserializer.class.getName(),
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://order-service-tests",
                // Genel amaçlı GenericRecord değil, şemadan üretilen sınıf dönsün.
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true"));
        return new KafkaConsumer<>(props);
    }
}
