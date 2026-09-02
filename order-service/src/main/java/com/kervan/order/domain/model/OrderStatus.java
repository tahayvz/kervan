package com.kervan.order.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Siparişin yaşam döngüsü.
 * <p>
 * İzin verilen geçişler burada tanımlıdır; kontrol tek yerde olduğu için yeni bir durum
 * eklendiğinde nereye dokunulacağı bellidir.
 */
public enum OrderStatus {

    /** Sipariş alındı, ödeme bekleniyor. */
    PLACED,
    /** Ödeme onaylandı, hazırlanıyor. */
    CONFIRMED,
    /** Kargoya verildi. */
    SHIPPED,
    /** Müşteriye teslim edildi. */
    DELIVERED,
    /** İptal edildi; teslim edilmiş sipariş iptal edilemez. */
    CANCELLED;

    private Set<OrderStatus> allowedNext;

    static {
        PLACED.allowedNext = EnumSet.of(CONFIRMED, CANCELLED);
        CONFIRMED.allowedNext = EnumSet.of(SHIPPED, CANCELLED);
        SHIPPED.allowedNext = EnumSet.of(DELIVERED);
        DELIVERED.allowedNext = EnumSet.noneOf(OrderStatus.class);
        CANCELLED.allowedNext = EnumSet.noneOf(OrderStatus.class);
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext.contains(next);
    }

    public boolean isFinal() {
        return allowedNext.isEmpty();
    }
}
