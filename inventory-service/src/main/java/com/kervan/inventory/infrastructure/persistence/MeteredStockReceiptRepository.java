package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockReceipt;
import com.kervan.inventory.domain.port.StockReceiptRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Mal kabullerini sayar.
 *
 * <h2>Hangi soruyu cevaplıyor?</h2>
 * "Ne kadar mal girdi?" Tek başına da anlamlı, ama asıl değeri düzeltme ölçümüyle
 * <b>birlikte</b> okununca ortaya çıkar: giren mala oranla yazılan zarar. O oran
 * yükseliyorsa depoda bir şey bozuluyordur ve bunu başka hiçbir sinyal söylemez.
 *
 * <p>Gerekçesi ve etiket kararları {@link MeteredStockAdjustmentRepository} ile aynı;
 * burada etiket yok çünkü mal kabulünün gerekçesi yok — "mal geldi" kendi gerekçesidir.
 */
@Component
@Primary
class MeteredStockReceiptRepository implements StockReceiptRepository {

    private final StockReceiptRepository delegate;
    private final DistributionSummary received;

    MeteredStockReceiptRepository(StockReceiptRepositoryAdapter delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.received = DistributionSummary.builder("kervan.stock.received")
                .description("Mal kabulleri: adet ve miktar")
                .baseUnit("items")
                .register(registry);
    }

    @Override
    public boolean saveIfNew(StockReceipt receipt) {
        boolean isNew = delegate.saveIfNew(receipt);
        if (isNew) {
            // Tekrar gonderilen makbuz sayilmaz: istemcinin yeniden denemesi gercek
            // bir mal hareketi degildir.
            received.record(receipt.quantity());
        }
        return isNew;
    }
}
