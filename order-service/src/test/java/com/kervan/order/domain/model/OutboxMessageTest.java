package com.kervan.order.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxMessage")
class OutboxMessageTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30Z");

    @Test
    @DisplayName("dışarıdan verilen dizi sonradan değiştirilse bile kayıt bozulmaz")
    void copiesPayloadOnConstruction() {
        byte[] original = {1, 2, 3};
        OutboxMessage message = OutboxMessage.pending("Order", "order-1", "OrderPlaced", original, NOW);

        original[0] = 99;

        assertThat(message.payload()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("dönen dizi değiştirilse bile kayıt bozulmaz")
    void copiesPayloadOnRead() {
        OutboxMessage message = OutboxMessage.pending(
                "Order", "order-1", "OrderPlaced", new byte[]{1, 2, 3}, NOW);

        message.payload()[0] = 99;

        assertThat(message.payload()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("aynı içerikli iki kayıt eşittir")
    void comparesPayloadByContent() {
        OutboxMessage first = new OutboxMessage(
                "m-1", "Order", "order-1", "OrderPlaced", new byte[]{1, 2, 3}, NOW, null);
        OutboxMessage second = new OutboxMessage(
                "m-1", "Order", "order-1", "OrderPlaced", new byte[]{1, 2, 3}, NOW, null);

        // Kayıt tipinin ürettiği equals diziyi referansa göre karşılaştırırdı;
        // içerikleri aynı olan bu ikisi eşit sayılmazdı.
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("farklı payload'lı kayıtlar eşit değildir")
    void differentPayloadsAreNotEqual() {
        OutboxMessage first = new OutboxMessage(
                "m-1", "Order", "order-1", "OrderPlaced", new byte[]{1, 2, 3}, NOW, null);
        OutboxMessage second = new OutboxMessage(
                "m-1", "Order", "order-1", "OrderPlaced", new byte[]{9, 9, 9}, NOW, null);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("toString ikili gövdeyi basmaz, boyutunu yazar")
    void toStringReportsPayloadSizeNotContent() {
        OutboxMessage message = OutboxMessage.pending(
                "Order", "order-1", "OrderPlaced", new byte[]{1, 2, 3, 4}, NOW);

        assertThat(message.toString())
                .contains("payloadBytes=4")
                .contains("aggregateId=order-1");
    }

    @Test
    @DisplayName("publishedAt yoksa kayıt yayınlanmamıştır")
    void tracksPublishedState() {
        OutboxMessage pending = OutboxMessage.pending(
                "Order", "order-1", "OrderPlaced", new byte[]{1}, NOW);

        assertThat(pending.isPublished()).isFalse();
        assertThat(new OutboxMessage("m-1", "Order", "order-1", "OrderPlaced",
                new byte[]{1}, NOW, NOW).isPublished()).isTrue();
    }
}
