package com.kervan.payment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Bir sipariş için yapılmış tahsilat.
 *
 * <p>Bir siparişin en fazla bir ödemesi olur; bu kısıt veritabanında tanımlıdır ve
 * idempotentliğin kaynağıdır. Aynı komut iki kez işlenirse müşteriden iki kez tahsilat
 * yapılmaz.
 */
public record Payment(
        String id,
        String orderId,
        String customerId,
        Money amount,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Payment {
        Objects.requireNonNull(orderId, "orderId null olamaz");
        Objects.requireNonNull(customerId, "customerId null olamaz");
        Objects.requireNonNull(amount, "amount null olamaz");
        Objects.requireNonNull(status, "status null olamaz");
        Objects.requireNonNull(createdAt, "createdAt null olamaz");
    }

    /** Yeni, henüz kimliği atanmamış bir tahsilat. */
    public static Payment captured(String orderId, String customerId, Money amount, Instant now) {
        return new Payment(null, orderId, customerId, amount, PaymentStatus.CAPTURED, now, now);
    }

    public boolean isCaptured() {
        return status == PaymentStatus.CAPTURED;
    }

    /**
     * Ödemeyi iade edilmiş olarak işaretler.
     *
     * <p>Zaten iade edilmiş bir ödemeyi tekrar iade etmek müşteriye ikinci kez para
     * göndermek olurdu. Telafi komutu en az bir kez geleceği için bu <b>beklenen</b>
     * bir durumdur ve burada durdurulur.
     */
    public Payment refunded(Instant now) {
        if (status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Ödeme zaten iade edilmiş: orderId=" + orderId);
        }
        return new Payment(id, orderId, customerId, amount, PaymentStatus.REFUNDED, createdAt, now);
    }
}
