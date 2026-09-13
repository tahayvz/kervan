package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.AdjustmentReason;
import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.model.StockNotFoundException;
import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.port.AdjustmentPage;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
import com.kervan.inventory.domain.port.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sayım düzeltmesi (ADR-0021).
 *
 * <h2>Mal kabulünden farkı</h2>
 * {@code StockReceiptService} bir <b>olay</b> kaydeder: dışarıdan mal geldi. Bu servis
 * bir <b>iddia</b> kaydeder: sistemdeki sayı gerçeği yansıtmıyor. Fark üç somut kurala
 * dönüşüyor:
 *
 * <ol>
 *   <li><b>Gerekçe zorunlu.</b> Sınırlı bir listeden ({@link AdjustmentReason}), serbest
 *       metin değil. {@code OTHER} seçilirse açıklama da zorunlu.</li>
 *   <li><b>Sahibi kaydedilir.</b> Token'daki {@code sub}. Stok parayla ölçülür; kimin
 *       değiştirdiği bilinmiyorsa bu yetki denetlenemez.</li>
 *   <li><b>Stok kaydı yoksa hata.</b> Kabul, kaydı kendisi açar; düzeltme açamaz.
 *       Var olmayan bir sayı düzeltilemez.</li>
 * </ol>
 *
 * <h2>Ayrılmış miktara dokunulmaz</h2>
 * Düzeltme yalnızca satılabilir miktarı değiştirir. Ayrılmış miktar saga'nındır: orada
 * duran mal bir müşteriye söz verilmiştir. Gerekçesi {@code StockItem.adjust}'ta.
 *
 * <h2>İdempotentlik</h2>
 * Makbuzdaki desenin aynısı: istemcinin verdiği {@code adjustmentId} birincil anahtar,
 * karar {@code INSERT ... ON CONFLICT DO NOTHING} ile veritabanına bırakılıyor.
 */
@Service
public class StockAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);

    /** İstenmediğinde sayfa büyüklüğü. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * İstense de aşılmayan sayfa büyüklüğü.
     *
     * <p>Üst sınır olmasaydı tek bir istek yüz binlerce kaydı belleğe alırdı; bir
     * denetim ucunu böyle bırakmak, onu aynı zamanda bir hizmet dışı bırakma aracı
     * yapar.
     */
    public static final int MAX_PAGE_SIZE = 200;

    private final StockRepository stockRepository;
    private final StockAdjustmentRepository adjustments;
    private final Clock clock;

    public StockAdjustmentService(StockRepository stockRepository,
                                  StockAdjustmentRepository adjustments,
                                  Clock clock) {
        this.stockRepository = stockRepository;
        this.adjustments = adjustments;
        this.clock = clock;
    }

    /**
     * Düzeltmeyi uygular ve stoğun yeni hâlini döndürür.
     *
     * @throws StockNotFoundException SKU'nun stok kaydı hiç açılmamışsa
     * @throws IllegalArgumentException düzeltme satılabiliri eksiye düşürecekse,
     *     ya da {@code OTHER} gerekçesi açıklamasız gönderildiyse
     */
    @Transactional
    public StockItem adjust(String adjustmentId, String sku, int delta,
                            AdjustmentReason reason, String note, String adjustedBy) {
        requireNoteWhenOther(reason, note);

        // Kayıt YOKSA hata. createIfAbsent BİLEREK çağrılmıyor: mal kabulünden tek
        // yapısal fark bu ve kasten burada duruyor.
        //
        // NOT — burada KİLİT, tekrar denetiminden ÖNCE alınıyor; StockReceiptService'te
        // ise tam tersi. Tutarsız görünüyor, değil:
        //
        //   Makbuzda tekrar yolu hiç okuma yapmadan cevap verebiliyor, o yüzden kilide
        //   hiç girmiyor. Burada ise iki şey aynı okumadan çıkıyor: "kayıt var mı"
        //   (404 kararı) ve "kaç tane var" (düzeltmenin uygulanacağı değer). İkinciyi
        //   kilit altında okumak şart -- oku/hesapla/yaz arasına başka bir işlem
        //   girerse düzeltme kaybolur.
        //
        //   Ayırıp önce kilitsiz okumak mümkündü ama iki sorgu eder ve aynı satırı iki
        //   kez, biri kilitsiz biri kilitli okumak bu kodda yeni bir tuzak açar.
        //   Tekrarlanan bir düzeltmenin kısa süre kilit tutması, o riskten ucuz.
        StockItem current = stockRepository.lockAll(List.of(sku)).stream().findFirst()
                .orElseThrow(() -> new StockNotFoundException(sku));

        // DOGRULAMA, DEFTERE YAZMADAN ONCE.
        //
        // Once yazip sonra dogrulamak da "calisiyordu": gecersiz bir duzeltmede
        // istisna transaction'i geri aliyor ve satir kaybolyordu. Ama bu, olmayacak
        // bir isi deftere yazip sonra silmek demekti -- ve sayaci deftere yazma
        // anina baglayan her sey (asagidaki metrik sarmalayicisi) reddedilmis
        // duzeltmeleri de saymis olurdu.
        //
        // Simdi sira dogru: gecersizse hic yazilmaz.
        StockItem updated = current.adjust(delta);

        Instant now = clock.instant();
        boolean isNew = adjustments.saveIfNew(new StockAdjustment(
                adjustmentId, sku, delta, reason, note, adjustedBy, now));
        if (!isNew) {
            log.debug("Düzeltme zaten uygulanmış, tekrar edilmedi: adjustmentId={}", adjustmentId);
            return current;
        }

        stockRepository.saveAll(List.of(updated));

        // INFO seviyesinde ve tam: bu satır denetim izinin ikinci kopyası.
        log.info("Stok düzeltildi: sku={} delta={} sebep={} kim={} yeniStok={}",
                sku, delta, reason, adjustedBy, updated.available());
        return updated;
    }

    /**
     * Bir SKU'nun düzeltme geçmişi, en yeniden eskiye ve sayfalı.
     *
     * <p>Sayfalama anahtar tabanlı (keyset), {@code OFFSET} değil. Denetim izi ekleme
     * yapılan bir defterdir: {@code OFFSET} ile sayfa çevirirken araya yeni bir kayıt
     * girerse sınır kayar ve okuyan kişi bir kaydı iki kez görür ya da hiç görmez.
     * İkincisi bir denetim izinde kabul edilemez.
     *
     * @param cursor önceki sayfanın {@code nextCursor}'ı; ilk sayfa için {@code null}
     * @param size istenen sayfa büyüklüğü; {@code null} ise varsayılan, üst sınır
     *     {@link #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public AdjustmentPage history(String sku, String cursor, Integer size) {
        int limit = pageSize(size);
        AuditCursor.Decoded from = (cursor == null || cursor.isBlank())
                ? null : AuditCursor.decode(cursor);

        // Bir fazla istenip son kayıt atılıyor: "daha var mı" sorusunun cevabı ancak
        // böyle kesin bilinir. Sayfa tam dolduğu için "muhtemelen vardır" demek,
        // son sayfada boş bir sayfa daha çevirtir.
        List<StockAdjustment> rows = adjustments.findBySku(sku,
                from == null ? null : from.adjustedAt(),
                from == null ? null : from.adjustmentId(),
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<StockAdjustment> items = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore ? AuditCursor.of(items.get(items.size() - 1)) : null;
        return new AdjustmentPage(List.copyOf(items), next);
    }

    private int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("Sayfa büyüklüğü pozitif olmalı: " + requested);
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private void requireNoteWhenOther(AdjustmentReason reason, String note) {
        // Gerekçesiz bir "diğer", gerekçe yazmamakla aynı şeydir. Listeyi kalabalık
        // etmemek için OTHER duruyor, ama bedava değil.
        if (reason == AdjustmentReason.OTHER && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "Gerekçe OTHER seçildiğinde açıklama zorunludur");
        }
    }
}
