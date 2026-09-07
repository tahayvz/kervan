package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.StockItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stock_items")
class StockItemEntity {

    @Id
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItemEntity() {
        // JPA için
    }

    StockItemEntity(String sku, int availableQuantity, int reservedQuantity, Instant updatedAt) {
        this.sku = sku;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = reservedQuantity;
        this.updatedAt = updatedAt;
    }

    void apply(StockItem item, Instant now) {
        this.availableQuantity = item.available();
        this.reservedQuantity = item.reserved();
        this.updatedAt = now;
    }

    StockItem toDomain() {
        return new StockItem(sku, availableQuantity, reservedQuantity);
    }

    String getSku() {
        return sku;
    }
}
