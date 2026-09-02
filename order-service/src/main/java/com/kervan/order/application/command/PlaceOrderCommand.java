package com.kervan.order.application.command;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sipariş oluşturma isteği — domain'in kendi dilinde.
 * <p>
 * Web DTO'su değildir. Böylece bir Kafka tüketicisi ya da zamanlanmış iş de aynı
 * use-case'i, HTTP'ye ait bir tip üretmek zorunda kalmadan çağırabilir.
 */
public record PlaceOrderCommand(String customerId, String currency, List<Line> lines) {

    public record Line(String productId, String sku, int quantity, BigDecimal unitPrice) {
    }
}
