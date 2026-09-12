package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("MeteredPaymentEventPublisher")
class MeteredPaymentEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Money AMOUNT =
            new Money(new BigDecimal("249.90"), Currency.getInstance("TRY"));

    private AvroPaymentEventPublisher delegate;
    private SimpleMeterRegistry registry;
    private MeteredPaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(AvroPaymentEventPublisher.class);
        registry = new SimpleMeterRegistry();
        publisher = new MeteredPaymentEventPublisher(delegate, registry);
    }

    private double count(String result) {
        return registry.get("kervan.payments").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("tahsilat ve ret ayrı sayılır")
    void countsCaptureAndDeclineSeparately() {
        publisher.paymentProcessed("order-1", "pay-1", AMOUNT, NOW);
        publisher.paymentFailed("order-2", "limit asildi", NOW);
        publisher.paymentFailed("order-3", "limit asildi", NOW);

        assertThat(count("captured")).isEqualTo(1);
        assertThat(count("declined")).isEqualTo(2);
    }

    @Test
    @DisplayName("olay her durumda asıl yayıncıya iletilir")
    void alwaysDelegates() {
        publisher.paymentProcessed("order-1", "pay-1", AMOUNT, NOW);
        publisher.paymentRefunded("order-1", "pay-1", NOW);

        verify(delegate).paymentProcessed("order-1", "pay-1", AMOUNT, NOW);
        verify(delegate).paymentRefunded("order-1", "pay-1", NOW);
    }

    @Test
    @DisplayName("tutar metrik olarak yayınlanmaz")
    void doesNotPublishAmounts() {
        publisher.paymentProcessed("order-1", "pay-1", AMOUNT, NOW);

        // Ölçülen şey SAYIDIR. Para toplamını metrik deposunda tutmak, kesin
        // olması gereken bir veriyi örneklenebilen ve budanabilen bir depoya
        // taşımak olurdu; muhasebenin kaynağı veritabanıdır.
        assertThat(registry.find("kervan.payments.amount").meters()).isEmpty();
    }
}
