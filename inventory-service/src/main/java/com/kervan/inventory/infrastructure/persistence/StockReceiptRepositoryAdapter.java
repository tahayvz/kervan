package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockReceipt;
import com.kervan.inventory.domain.port.StockReceiptRepository;
import org.springframework.stereotype.Component;

@Component
class StockReceiptRepositoryAdapter implements StockReceiptRepository {

    private final SpringDataStockReceiptRepository repository;

    StockReceiptRepositoryAdapter(SpringDataStockReceiptRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean saveIfNew(StockReceipt receipt) {
        return repository.insertIfNew(
                receipt.receiptId(), receipt.sku(), receipt.quantity(), receipt.receivedAt()) == 1;
    }
}
