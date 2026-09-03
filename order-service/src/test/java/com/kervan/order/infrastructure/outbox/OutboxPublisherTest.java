package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private KafkaTemplate<String, String> kafkaTemplate;
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
                TOPIC,
                100,
                MAX_ATTEMPTS,
                Duration.ofSeconds(5));
    }

    private OutboxMessage message(String id, String aggregateId) {
        return new OutboxMessage(id, "Order", aggregateId, "OrderPlaced",
                "{\"orderId\":\"" + aggregateId + "\"}", NOW, null);
    }

    private void kafkaAccepts() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void kafkaRejects(String reason) {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(reason)));
    }

    @Test
    void shouldPublishWithAggregateIdAsKey() {
        when(outboxRepository.lockDeliverable(100, MAX_ATTEMPTS))
                .thenReturn(List.of(message("m-1", "order-1")));
        kafkaAccepts();

        publisher.publishPending();

        verify(kafkaTemplate).send(TOPIC, "order-1", "{\"orderId\":\"order-1\"}");
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

        verify(kafkaTemplate).send(TOPIC, "order-1", "{\"orderId\":\"order-1\"}");
        verify(kafkaTemplate, never()).send(TOPIC, "order-2", "{\"orderId\":\"order-2\"}");
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

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
