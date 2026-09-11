package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutboxPublisher")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String TOPIC = "kervan.orders.events";
    private static final int MAX_ATTEMPTS = 5;

    private OutboxRepository outboxRepository;
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new OutboxPublisher(
                outboxRepository,
                kafkaTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                100,
                MAX_ATTEMPTS,
                Duration.ofSeconds(5));
    }

    private OutboxMessage message(String id, String aggregateId) {
        return message(id, aggregateId, null);
    }

    private OutboxMessage message(String id, String aggregateId, String traceParent) {
        return new OutboxMessage(id, "Order", aggregateId, "OrderPlaced", TOPIC,
                payloadOf(aggregateId), NOW, null, traceParent);
    }

    /**
     * Gönderilen tek kaydı yakalar. Kayıt üzerinden doğrulama yapılıyor çünkü
     * yayıncı artık yalnızca konu/anahtar/gövde değil, başlık da yazıyor.
     */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, byte[]> onlySentRecord() {
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    /**
     * Gerçekte burada Avro baytları olur. Bu sınıfın konusu taşıma davranışıdır,
     * içerik değil; bu yüzden sipariş başına ayırt edilebilir sabit bir bayt dizisi
     * yeterlidir.
     */
    private static byte[] payloadOf(String aggregateId) {
        return aggregateId.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void kafkaAccepts() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @SuppressWarnings("unchecked")
    private void kafkaRejects(String reason) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(reason)));
    }

    @Test
    void shouldPublishWithAggregateIdAsKey() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaAccepts();

        publisher.publishPending();

        ProducerRecord<String, byte[]> sent = onlySentRecord();
        assertThat(sent.topic()).isEqualTo(TOPIC);
        assertThat(sent.key()).isEqualTo("order-1");
        assertThat(sent.value()).isEqualTo(payloadOf("order-1"));
    }

    @Test
    @DisplayName("satırdaki izleme bağlamı traceparent başlığı olarak gider")
    void shouldCarryTraceParentFromRow() {
        String traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1", traceParent)));
        kafkaAccepts();

        publisher.publishPending();

        // Bağlam satırdan gelir, bu iş parçacığından DEĞİL: yayıncı zamanlanmış bir
        // iştir ve onun izi siparişi alan istekle ilgisizdir.
        assertThat(onlySentRecord().headers().lastHeader("traceparent"))
                .isNotNull()
                .extracting(header -> new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(traceParent);
    }

    @Test
    @DisplayName("satırda iz yoksa başlık da yok; olay yine gönderilir")
    void shouldPublishWithoutTraceParentWhenRowHasNone() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaAccepts();

        publisher.publishPending();

        // İzleme bir teşhis aracıdır; yokluğu olayın yayınlanmasını engellemez.
        assertThat(onlySentRecord().headers().lastHeader("traceparent")).isNull();
        verify(outboxRepository).markPublished("m-1", NOW);
    }

    @Test
    void shouldMarkPublishedAfterKafkaAccepts() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaAccepts();

        publisher.publishPending();

        verify(outboxRepository).markPublished("m-1", NOW);
    }

    @Test
    @DisplayName("gönderim başarısızsa yayınlandı olarak işaretlenmez")
    void shouldNotMarkPublishedWhenSendFails() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaRejects("broker erişilemiyor");

        publisher.publishPending();

        verify(outboxRepository, never()).markPublished(anyString(), any());
    }

    @Test
    @DisplayName("başarısız gönderim deneme sayacına işlenir")
    void shouldRecordFailedAttempt() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaRejects("broker erişilemiyor");

        publisher.publishPending();

        verify(outboxRepository).recordFailedAttempt(eq("m-1"), eq(NOW), anyString());
    }

    @Test
    @DisplayName("bir kayıt patlayınca tur durur; sonraki kayıt öne geçmez")
    void shouldStopRoundAfterFailure() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1"), message("m-2", "order-2")));
        kafkaRejects("broker erişilemiyor");

        publisher.publishPending();

        // Tek gönderim oldu ve o da ilk kayıttı: ikinci kayıt öne geçmedi.
        assertThat(onlySentRecord().key()).isEqualTo("order-1");
    }

    @Test
    @DisplayName("deneme sınırı depoya iletilir; kenara alınmış kayıtlar hiç okunmaz")
    void shouldPassMaxAttemptsToRepository() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS)).thenReturn(List.of());

        publisher.publishPending();

        verify(outboxRepository).lockDeliverable(100, MAX_ATTEMPTS);
    }

    @Test
    void shouldDoNothingWhenNothingIsPending() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS)).thenReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }
}
