package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
class StockAdjustmentRepositoryAdapter implements StockAdjustmentRepository {

    private final SpringDataStockAdjustmentRepository repository;

    StockAdjustmentRepositoryAdapter(SpringDataStockAdjustmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean saveIfNew(StockAdjustment adjustment) {
        return repository.insertIfNew(
                adjustment.adjustmentId(), adjustment.sku(), adjustment.delta(),
                adjustment.reason().name(), adjustment.note(),
                adjustment.adjustedBy(), adjustment.adjustedAt()) == 1;
    }

    /**
     * İlk sayfa ile sonraki sayfa AYRI sorgular: ilkinde bir sınır yok, sonrakinde
     * "şu noktadan öncekiler" var. Tek sorguda birleştirmek için sınır alanlarına
     * yapay bir "en büyük değer" vermek gerekirdi ve o değerin ne olduğu her zaman
     * tartışmalıdır.
     */
    @Override
    public List<StockAdjustment> findBySku(String sku, Instant beforeAt, String beforeId, int limit) {
        List<StockAdjustmentEntity> rows = (beforeAt == null || beforeId == null)
                ? repository.findBySkuOrderByAdjustedAtDescAdjustmentIdDesc(sku, Limit.of(limit))
                : repository.findPageBefore(sku, beforeAt, beforeId, Limit.of(limit));
        return rows.stream().map(StockAdjustmentEntity::toDomain).toList();
    }
}
