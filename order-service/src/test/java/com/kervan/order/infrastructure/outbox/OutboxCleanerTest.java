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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private static final int MAX_BATCHES = 3;

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);

    private OutboxCleaner cleaner(boolean publisherEnabled) {
        return new OutboxCleaner(outboxRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                RETENTION, BATCH, MAX_BATCHES, publisherEnabled);
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

    @Test
    @DisplayName("parti dolduğu sürece aynı turda devam eder")
    void keepsDeletingWhileBatchesComeBackFull() {
        // İki dolu parti, sonra yarım: üçüncüsünde silinecek kayıt kalmamıştır.
        when(outboxRepository.deletePublishedBefore(CUTOFF, BATCH))
                .thenReturn(BATCH, BATCH, 7);

        cleaner(true).deleteExpired();

        // Turda tek parti silinseydi temizlik hızı saatte 1000 satırda kalırdı;
        // sipariş hızı bunu geçtiği anda tablo büyümeye devam ederdi.
        verify(outboxRepository, times(3)).deletePublishedBefore(CUTOFF, BATCH);
    }

    @Test
    @DisplayName("parti yarım gelirse boşuna bir sorgu daha çalıştırılmaz")
    void stopsAsSoonAsABatchComesBackPartial() {
        when(outboxRepository.deletePublishedBefore(CUTOFF, BATCH)).thenReturn(3);

        cleaner(true).deleteExpired();

        verify(outboxRepository, times(1)).deletePublishedBefore(CUTOFF, BATCH);
    }

    @Test
    @DisplayName("tur sınırına takılırsa durur — sonsuza kadar silmeye çalışmaz")
    void stopsAtTheRunLimit() {
        // Her parti dolu dönüyor: silinecek kayıt bitmiyor.
        when(outboxRepository.deletePublishedBefore(CUTOFF, BATCH)).thenReturn(BATCH);

        cleaner(true).deleteExpired();

        // Sınıra takılmak "temizlik yetişemiyor" demektir; kod bunu uyarı olarak
        // loglar ve turu bitirir.
        verify(outboxRepository, times(MAX_BATCHES)).deletePublishedBefore(CUTOFF, BATCH);
    }
}
