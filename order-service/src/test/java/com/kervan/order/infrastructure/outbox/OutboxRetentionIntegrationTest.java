package com.kervan.order.infrastructure.outbox;

import com.kervan.order.AbstractIntegrationTest;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temizliğin gerçek PostgreSQL üzerinde doğru satırları sildiğini doğrular.
 *
 * <p>Ölçütün seçimi {@code OutboxCleanerTest}'te; burada asıl SQL'in ne yaptığı
 * denetleniyor. İkisi ayrı: doğru ölçüdü seçip yanlış satırı silmek de mümkün.
 *
 * <p>Yayıncı ve temizlik zamanlayıcıları bu sınıfta kapalı. Açık olsalardı arka planda
 * çalışıp test verisini damgalar ya da silerlerdi; test ne ölçtüğünü bilemezdi.
 */
@TestPropertySource(properties = {
        "kervan.outbox.publisher.enabled=false",
        "kervan.outbox.cleanup.enabled=false"
})
@DisplayName("Outbox saklama süresi")
class OutboxRetentionIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-03-03T12:00:00Z");
    private static final Instant OLD = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-03-09T12:00:00Z");

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SpringDataOutboxRepository springDataRepository;

    @BeforeEach
    void clearTable() {
        springDataRepository.deleteAll();
    }

    @Test
    @DisplayName("yayıncı modunda yalnızca damgalanmış eski kayıtlar silinir")
    void deletesOnlyPublishedRows() {
        String oldPublished = save(OLD, NOW);
        String oldUnpublished = save(OLD, null);
        String recentPublished = save(RECENT, NOW);

        int deleted = outboxRepository.deletePublishedBefore(CUTOFF, 100);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingAggregateIds())
                // Damgasız eski kayıt gönderilememiş demektir; silinmesi olayın
                // kaybolması olurdu. Yeni kayıt zaten saklama penceresinin içinde.
                .containsExactlyInAnyOrder(oldUnpublished, recentPublished)
                .doesNotContain(oldPublished);
    }

    @Test
    @DisplayName("Debezium modunda eski kayıtlar damgasız da olsa silinir")
    void deletesEveryExpiredRow() {
        String oldPublished = save(OLD, NOW);
        String oldUnpublished = save(OLD, null);
        String recentUnpublished = save(RECENT, null);

        int deleted = outboxRepository.deleteAllBefore(CUTOFF, 100);

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingAggregateIds())
                .containsExactly(recentUnpublished)
                .doesNotContain(oldPublished, oldUnpublished);
    }

    @Test
    @DisplayName("bir turda en fazla parti boyutu kadar satır silinir")
    void respectsBatchSize() {
        for (int i = 0; i < 5; i++) {
            save(OLD, NOW);
        }

        int deleted = outboxRepository.deletePublishedBefore(CUTOFF, 2);

        // Sınırsız bir DELETE tabloyu uzun süre kilitler. Kalanlar bir sonraki turda.
        assertThat(deleted).isEqualTo(2);
        assertThat(springDataRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("silinecek kayıt yoksa sorgu boşa çalışmaz")
    void deletesNothingWhenAllRowsAreRecent() {
        save(RECENT, NOW);

        assertThat(outboxRepository.deletePublishedBefore(CUTOFF, 100)).isZero();
        assertThat(springDataRepository.count()).isEqualTo(1);
    }

    private String save(Instant occurredAt, Instant publishedAt) {
        String aggregateId = UUID.randomUUID().toString();
        outboxRepository.save(new OutboxMessage(
                null, "Order", aggregateId, "OrderPlaced",
                "payload".getBytes(StandardCharsets.UTF_8), occurredAt, publishedAt));
        return aggregateId;
    }

    private java.util.List<String> remainingAggregateIds() {
        return springDataRepository.findAll().stream()
                .map(OutboxEntity::getAggregateId)
                .toList();
    }
}
