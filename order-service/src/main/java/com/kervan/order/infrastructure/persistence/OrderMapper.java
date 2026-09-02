package com.kervan.order.infrastructure.persistence;

import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderLine;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Domain modeli ile JPA entity'si arasında dönüşüm. */
final class OrderMapper {

    private OrderMapper() {
    }

    static OrderEntity toEntity(Order order, UUID id) {
        OrderEntity entity = new OrderEntity(
                id,
                order.customerId(),
                order.totalAmount().amount(),
                order.totalAmount().currency().getCurrencyCode(),
                order.status(),
                order.placedAt(),
                order.updatedAt());

        for (OrderLine line : order.lines()) {
            entity.addLine(new OrderLineEntity(
                    line.productId(), line.sku(), line.quantity(), line.unitPrice().amount()));
        }
        return entity;
    }

    static Order toDomain(OrderEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());

        List<OrderLine> lines = entity.getLines().stream()
                .map(line -> new OrderLine(
                        line.getProductId(),
                        line.getSku(),
                        line.getQuantity(),
                        new Money(line.getUnitPrice(), currency)))
                .toList();

        return Order.restore(
                entity.getId().toString(),
                entity.getCustomerId(),
                lines,
                new Money(entity.getTotalAmount(), currency),
                entity.getStatus(),
                entity.getPlacedAt(),
                entity.getUpdatedAt());
    }
}
