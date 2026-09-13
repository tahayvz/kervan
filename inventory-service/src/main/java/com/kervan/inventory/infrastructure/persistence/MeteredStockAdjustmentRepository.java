package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Stok düzeltmelerini sayar.
 *
 * <h2>Hangi soruyu cevaplıyor?</h2>
 * "Ne kadar stok, hangi gerekçeyle yazılıyor?" Bu teknik değil <b>ticari</b> bir
 * sinyaldir ve hiçbir hata log'una düşmez: her düzeltme tek tek meşrudur, sorun
 * <em>eğilimdedir</em>. {@code SHRINKAGE} bir haftada on katına çıktıysa depoda bir
 * şey oluyordur; {@code COUNT_CORRECTION} sürekli artıyorsa sayım süreci bozuktur.
 *
 * <h2>Neden {@link DistributionSummary}, sayaç değil?</h2>
 * İki farklı soru var ve bir sayaç yalnızca birini cevaplar:
 * <ul>
 *   <li>"Kaç düzeltme yapıldı?" → {@code _count}</li>
 *   <li>"Toplam kaç adet yazıldı?" → {@code _sum}</li>
 * </ul>
 * Yüz tane bir adetlik düzeltme ile tek bir yüz adetlik düzeltme aynı şey değildir;
 * biri süreç sorunu, diğeri olay. {@code DistributionSummary} ikisini tek ölçümde verir.
 *
 * <h2>Etiketler</h2>
 * {@code reason} sınırlı bir listeden (altı değer) ve {@code direction} iki değer:
 * en fazla on iki zaman serisi. SKU <b>etiket değildir</b> — ürün sayısı kadar seri
 * açardı ve bu depoda kardinalite kuralı zaten yazılı (ADR-0014).
 *
 * <h2>Neden sarmalayıcı?</h2>
 * {@code MeteredInventoryEventPublisher} ile aynı gerekçe: ölçüm, işi yapan sınıfın
 * içine sızmamalı. Ölçüm noktası da aynı mantıkla seçildi — <b>gerçekten olan</b> şey
 * deftere yazmaktır. Tekrar gönderilen bir düzeltme {@code false} döner ve sayılmaz;
 * doğrusu budur, yoksa istemcinin yeniden denemeleri gerçek stok hareketi gibi
 * görünürdü.
 */
@Component
@Primary
class MeteredStockAdjustmentRepository implements StockAdjustmentRepository {

    private static final String METRIC = "kervan.stock.adjusted";

    private final StockAdjustmentRepository delegate;
    private final MeterRegistry registry;

    MeteredStockAdjustmentRepository(StockAdjustmentRepositoryAdapter delegate,
                                     MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public boolean saveIfNew(StockAdjustment adjustment) {
        boolean isNew = delegate.saveIfNew(adjustment);
        if (isNew) {
            // Ölçüm yazmadan SONRA: yazma patlarsa hiçbir şey olmamıştır.
            summary(adjustment).record(Math.abs((long) adjustment.delta()));
        }
        return isNew;
    }

    @Override
    public List<StockAdjustment> findBySku(String sku, Instant beforeAt, String beforeId, int limit) {
        return delegate.findBySku(sku, beforeAt, beforeId, limit);
    }

    private DistributionSummary summary(StockAdjustment adjustment) {
        return DistributionSummary.builder(METRIC)
                .description("Stok duzeltmeleri: adet ve miktar")
                .baseUnit("items")
                .tag("reason", adjustment.reason().name())
                // Yön ayrı bir etiket: gerekçeden türetilemez. COUNT_CORRECTION iki
                // yöne de gidebilir ve "eksilen mi arttı, artan mı" ayrı bir sorudur.
                .tag("direction", adjustment.delta() < 0 ? "out" : "in")
                .register(registry);
    }
}
