package com.kervan.order.infrastructure.outbox;

import com.kervan.order.domain.port.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Temizliğin doğru ölçütü seçtiğini doğrular.
 *
 * <p>Buradaki tek soru şu: kayıtları kim taşıyor? Uygulama içi yayıncı taşıyorsa
 * yalnızca damgalanmış kayıtlar silinebilir; damgasız eski bir kayıt gönderilememiş
 * demektir ve silinmesi olayı kaybetmek olurdu. Debezium taşıyorsa damga hiç
 * konmadığı için tek ölçüt yaştır.
 *
 * <p>Yanlış ölçüt sessizce veri kaybettirir: hata vermez, testler yeşil kalır,
 * yalnızca bazı olaylar hiç yayınlanmamış olur.
 */
@DisplayName("OutboxCleaner")
class OutboxCleanerTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Instant CUTOFF = Instant.parse("2026-03-03T12:00:00Z");
    private static final int BATCH = 1000;

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);

    private OutboxCleaner cleaner(boolean publisherEnabled) {
        return new OutboxCleaner(outboxRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                RETENTION, BATCH, publisherEnabled);
    }

    @Test
    @DisplayName("uygulama içi yayıncı açıkken yalnızca yayınlanmış kayıtlar silinir")
    void deletesOnlyPublishedWhenPublisherRuns() {
        cleaner(true).deleteExpired();

        verify(outboxRepository).deletePublishedBefore(CUTOFF, BATCH);
        verify(outboxRepository, never()).deleteAllBefore(eq(CUTOFF), anyInt());
    }

    @Test
    @DisplayName("Debezium devredeyken ölçüt yaştır")
    void deletesByAgeWhenDebeziumCarries() {
        // Debezium publishedAt damgalamaz; "yayınlanmış" filtresi hiçbir kaydı
        // seçmez ve tablo sonsuza kadar büyürdü.
        cleaner(false).deleteExpired();

        verify(outboxRepository).deleteAllBefore(CUTOFF, BATCH);
        verify(outboxRepository, never()).deletePublishedBefore(eq(CUTOFF), anyInt());
    }

    @Test
    @DisplayName("sınır, saklama penceresi kadar geçmişe konur")
    void cutoffIsRetentionWindowBeforeNow() {
        cleaner(true).deleteExpired();

        // NOW - 7 gün. Saatin testte sabitlenmesi bu iddiayı kesinleştiriyor.
        verify(outboxRepository).deletePublishedBefore(NOW.minus(RETENTION), BATCH);
    }
}
