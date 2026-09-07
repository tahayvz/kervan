package com.kervan.order.infrastructure.messaging;

import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.OrderLine;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Her mesajın doğru konuya gittiğini ve şemasının doğru ad altına kaydedildiğini
 * doğrular.
 *
 * <p>Bu, gözle görülmesi zor bir hata sınıfını yakalar: yanlış hedef yazılan bir komut
 * derlenir, testler geçer, mesaj yanlış konuya düşer ve o konuyu kimse dinlemediği için
 * saga sessizce asılı kalır.
 */
@DisplayName("AvroOrderMessagePublisher")
class AvroOrderMessagePublisherTest {

    private static final String SCOPE = "order-publisher-test";
    private static final String REGISTRY_URL = "mock://" + SCOPE;
    private static final String ORDER_EVENTS = "kervan.orders.events";
    private static final String INVENTORY_COMMANDS = "kervan.inventory.commands";
    private static final String PAYMENT_COMMANDS = "kervan.payments.commands";
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Money AMOUNT =
            new Money(new BigDecimal("249.90"), Currency.getInstance("TRY"));

    private OutboxRepository outbox;
    private AvroOrderMessagePublisher publisher;
    private KafkaAvroDeserializer deserializer;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxRepository.class);
        publisher = new AvroOrderMessagePublisher(outbox, ORDER_EVENTS, INVENTORY_COMMANDS,
                PAYMENT_COMMANDS, REGISTRY_URL, true);
        deserializer = new KafkaAvroDeserializer();
        deserializer.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL,
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true"), false);
    }

    @AfterEach
    void tearDown() {
        deserializer.close();
        MockSchemaRegistry.dropScope(SCOPE);
    }

    private OutboxMessage written() {
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("sipariş olayları sipariş konusuna gider")
    void orderEventsGoToTheOrderTopic() {
        publisher.orderPlaced(event(), NOW);

        OutboxMessage message = written();
        assertThat(message.destination()).isEqualTo(ORDER_EVENTS);
        assertThat(message.eventType()).isEqualTo("OrderPlaced");
        assertThat(message.aggregateId()).isEqualTo("order-1");
    }

    @Test
    @DisplayName("stok komutu stok servisinin komut konusuna gider")
    void stockCommandsGoToTheInventoryTopic() {
        publisher.reserveStock("order-1", List.of(line()), NOW);

        OutboxMessage message = written();
        // Yanlış hedef yazılsaydı komut kimsenin dinlemediği bir konuya düşer ve
        // saga sessizce asılı kalırdı.
        assertThat(message.destination()).isEqualTo(INVENTORY_COMMANDS);
        assertThat(message.eventType()).isEqualTo("ReserveStock");
    }

    @Test
    @DisplayName("ödeme komutu ödeme servisinin komut konusuna gider")
    void paymentCommandsGoToThePaymentTopic() {
        publisher.processPayment("order-1", "c-1", AMOUNT, NOW);

        OutboxMessage message = written();
        assertThat(message.destination()).isEqualTo(PAYMENT_COMMANDS);
        assertThat(message.eventType()).isEqualTo("ProcessPayment");
    }

    @Test
    @DisplayName("telafi komutları da kendi hedeflerine gider")
    void compensationCommandsAreRoutedToo() {
        publisher.releaseStock("order-1", "res-1", NOW);
        assertThat(written().destination()).isEqualTo(INVENTORY_COMMANDS);
    }

    @Test
    @DisplayName("mesaj Avro olarak çözülebiliyor")
    void payloadIsReadableAvro() {
        publisher.processPayment("order-1", "c-1", AMOUNT, NOW);

        OutboxMessage message = written();
        Object decoded = deserializer.deserialize(message.destination(), message.payload());

        assertThat(decoded).isInstanceOf(com.kervan.contracts.payment.v1.ProcessPayment.class);
        var command = (com.kervan.contracts.payment.v1.ProcessPayment) decoded;
        assertThat(command.getOrderId()).isEqualTo("order-1");
        assertThat(command.getAmount()).isEqualByComparingTo("249.90");
        assertThat(command.getCurrency()).isEqualTo("TRY");
    }

    @Test
    @DisplayName("şema, hedef konu ve kayıt adından türeyen subject altına kaydedilir")
    void registersSchemaUnderTheDestinationSubject() throws Exception {
        publisher.reserveStock("order-1", List.of(line()), NOW);

        SchemaRegistryClient client = MockSchemaRegistry.getClientForScope(SCOPE);

        // Subject hedef konudan türer. Sipariş konusundan türeseydi, aynı komut
        // tipini iki servis farklı adlar altında kaydeder ve uyumluluk geçmişi
        // parçalanırdı.
        assertThat(client.getAllSubjects()).containsExactly(
                INVENTORY_COMMANDS + "-com.kervan.contracts.inventory.v1.ReserveStock");
    }

    private static OrderPlaced event() {
        return new OrderPlaced("order-1", "c-1", new BigDecimal("249.90"), "TRY",
                List.of(new OrderPlaced.Item("p-1", "SKU-1", 2, new BigDecimal("124.95"))),
                NOW);
    }

    private static OrderLine line() {
        return new OrderLine("p-1", "SKU-1", 2,
                new Money(new BigDecimal("124.95"), Currency.getInstance("TRY")));
    }
}
