package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockAdjustment;
import com.kervan.inventory.domain.port.StockAdjustmentRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

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

    @Override
    public List<StockAdjustment> findBySku(String sku, int limit) {
        return repository.findBySkuOrderByAdjustedAtDesc(sku, Limit.of(limit)).stream()
                .map(StockAdjustmentEntity::toDomain)
                .toList();
    }
}
