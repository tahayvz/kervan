package com.kervan.order.domain.model;

/**
 * Tanımlı olmayan bir durum geçişi denendi.
 * <p>
 * Kendi tipi var çünkü bu, çağıranın düzeltebileceği bir iş kuralı ihlalidir (HTTP 409).
 * Genel {@code IllegalStateException} kullanmak, sunucu tarafı programlama hatalarını
 * da aynı torbaya atardı; o zaman bir iç hata istemciye "çakışma" diye bildirilir ve
 * 5xx alarmı hiç çalmazdı.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Geçersiz durum geçişi: " + from + " -> " + to);
    }
}
