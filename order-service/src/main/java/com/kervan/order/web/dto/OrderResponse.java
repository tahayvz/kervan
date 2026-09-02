package com.kervan.order.web.dto;

import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String id,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        List<Line> lines,
        Instant placedAt) {

    public record Line(String productId, String sku, int quantity, BigDecimal unitPrice) {
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(),
                order.customerId(),
                order.totalAmount().amount(),
                order.totalAmount().currency().getCurrencyCode(),
                order.status(),
                order.lines().stream()
                        .map(line -> new Line(
                                line.productId(),
                                line.sku(),
                                line.quantity(),
                                line.unitPrice().amount()))
                        .toList(),
                order.placedAt());
    }
}
