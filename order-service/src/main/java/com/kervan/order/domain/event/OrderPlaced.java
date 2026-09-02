package com.kervan.order.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Sipariş oluşturulduğunda yayınlanan olay.
 * <p>
 * <b>Neden domain modelinin kendisi değil de ayrı bir tip?</b> Olay, servis sınırının
 * dışına çıkan bir sözleşmedir. {@code Order} sınıfı iç ihtiyaçla değişir; olay ise
 * onu dinleyen başka servisleri kırmadan değişemez. İkisini ayırmak, iç modeli
 * değiştirirken dış sözleşmeyi sabit tutmayı mümkün kılar.
 */
public record OrderPlaced(
        String orderId,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        List<Item> items,
        Instant placedAt) {

    public record Item(String productId, String sku, int quantity, BigDecimal unitPrice) {
    }

    /** Kafka konusuna yazılırken kullanılan olay tipi adı. */
    public static final String EVENT_TYPE = "OrderPlaced";
}
