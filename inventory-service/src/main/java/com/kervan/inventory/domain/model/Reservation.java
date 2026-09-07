package com.kervan.inventory.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Bir sipariş için tutulan stok.
 *
 * <h2>Neden ayırma kaydı tutuluyor?</h2>
 * Saga bir adım başarısız olduğunda öncekileri geri alır. Geri almak için ne kadarının
 * tutulduğunu bilmek gerekir; yalnızca stok sayısını düşseydik bu bilgi kaybolurdu.
 *
 * <p>Bir siparişin en fazla bir ayırması olur ve bu kısıt veritabanında tanımlıdır.
 * İdempotentlik oradan gelir: aynı komut iki kez işlenirse ikincisi kısıta takılır.
 * Ayrı bir "işlenmiş mesajlar" tablosuna gerek kalmaz — ki onun da temizlenmesi ve
 * doğru anahtarla yazılması gerekirdi.
 */
public record Reservation(
        String id,
        String orderId,
        List<ReservationLine> lines,
        ReservationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Reservation {
        Objects.requireNonNull(orderId, "orderId null olamaz");
        Objects.requireNonNull(status, "status null olamaz");
        Objects.requireNonNull(createdAt, "createdAt null olamaz");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Ayırma en az bir kalem içermeli");
        }
        lines = List.copyOf(lines);
    }

    /** Yeni, henüz kimliği atanmamış bir ayırma. */
    public static Reservation active(String orderId, List<ReservationLine> lines, Instant now) {
        return new Reservation(null, orderId, lines, ReservationStatus.ACTIVE, now, now);
    }

    public boolean isActive() {
        return status == ReservationStatus.ACTIVE;
    }

    /**
     * Ayırmayı geri bırakılmış olarak işaretler.
     *
     * <p>Zaten geri bırakılmış bir ayırmayı tekrar geri bırakmak stok yaratırdı: aynı
     * miktar iki kez satılabilire eklenirdi. Telafi komutu en az bir kez geleceği için
     * bu durum <b>beklenen</b> bir durumdur ve burada durdurulur.
     */
    public Reservation released(Instant now) {
        if (status == ReservationStatus.RELEASED) {
            throw new IllegalStateException(
                    "Ayırma zaten geri bırakılmış: orderId=" + orderId);
        }
        return new Reservation(id, orderId, lines, ReservationStatus.RELEASED, createdAt, now);
    }
}
