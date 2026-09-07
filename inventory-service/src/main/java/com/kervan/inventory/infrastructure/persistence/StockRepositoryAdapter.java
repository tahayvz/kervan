package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.port.StockRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.List;

@Component
class StockRepositoryAdapter implements StockRepository {

    private final SpringDataStockRepository repository;
    private final Clock clock;

    StockRepositoryAdapter(SpringDataStockRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public List<StockItem> lockAll(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return repository.lockBySkus(skus).stream().map(StockItemEntity::toDomain).toList();
    }

    /**
     * Satırlar aynı transaction'da {@code lockAll} ile okundu; burada bulunan nesneler
     * persistence context'ten gelir, yeni bir sorgu çalışmaz. Alanları değiştirmek
     * yeterli — Hibernate commit'te kendisi yazar.
     */
    @Override
    public void saveAll(Collection<StockItem> items) {
        for (StockItem item : items) {
            repository.findById(item.sku())
                    .orElseThrow(() -> new IllegalStateException(
                            "Kilitlenmiş stok satırı kayboldu: " + item.sku()))
                    .apply(item, clock.instant());
        }
    }
}
