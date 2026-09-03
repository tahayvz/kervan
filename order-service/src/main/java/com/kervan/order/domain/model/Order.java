package com.kervan.order.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Sipariş — sipariş bounded context'inin aggregate root'u.
 * <p>
 * <b>Framework'süz:</b> Bu sınıfta JPA veya Spring anotasyonu yoktur. Kalıcılık
 * {@code infrastructure.persistence.OrderEntity} ile eşlenir. Böylece iş kuralları
 * veritabanı olmadan test edilebilir (hexagonal mimari, ADR-0001).
 * <p>
 * <b>Toplam neden saklanıyor, her seferinde hesaplanmıyor?</b> Saklanıyor <em>ve</em>
 * satırlardan hesaplanıyor: {@link #place} toplamı satırlardan üretir. Dışarıdan
 * verilen bir toplam kabul edilmez, çünkü o zaman satırlarla toplam birbirini
 * tutmayabilir ve hangisinin doğru olduğu belirsizleşir.
 */
public class Order {

    private final String id;                 // kaydedilene kadar null
    private final String customerId;
    private final List<OrderLine> lines;
    private final Money totalAmount;
    private OrderStatus status;
    private final Instant placedAt;
    private Instant updatedAt;

    private Order(String id, String customerId, List<OrderLine> lines, Money totalAmount,
                  OrderStatus status, Instant placedAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.lines = List.copyOf(lines);
        this.totalAmount = totalAmount;
        this.status = status;
        this.placedAt = placedAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Yeni sipariş oluşturur. Toplam, satırlardan hesaplanır.
     *
     * @throws IllegalArgumentException satır listesi boşsa ya da satırlar farklı para
     *                                  birimleri taşıyorsa
     */
    public static Order place(String customerId, List<OrderLine> lines, Instant now) {
        Objects.requireNonNull(customerId, "customerId null olamaz");
        Objects.requireNonNull(now, "now null olamaz");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Sipariş en az bir satır içermeli");
        }

        Money total = sumOf(lines);
        return new Order(null, customerId, lines, total, OrderStatus.PLACED, now, now);
    }

    /** Kalıcılık katmanının kayıtlı bir siparişi geri yüklemesi için. */
    public static Order restore(String id, String customerId, List<OrderLine> lines,
                                Money totalAmount, OrderStatus status,
                                Instant placedAt, Instant updatedAt) {
        return new Order(id, customerId, lines, totalAmount, status, placedAt, updatedAt);
    }

    private static Money sumOf(List<OrderLine> lines) {
        Currency currency = lines.get(0).unitPrice().currency();
        Money total = Money.zero(currency);
        for (OrderLine line : lines) {
            total = total.add(line.lineTotal());   // farklı para birimi burada hata verir
        }
        return total;
    }

    /**
     * Durumu değiştirir.
     *
     * @throws InvalidStatusTransitionException geçiş {@link OrderStatus} tablosunda
     *                                          tanımlı değilse
     */
    public void changeStatus(OrderStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public String id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public List<OrderLine> lines() {
        return new ArrayList<>(lines);
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
