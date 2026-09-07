package com.kervan.inventory.infrastructure.persistence;

import com.kervan.inventory.domain.model.Reservation;
import com.kervan.inventory.domain.port.ReservationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class ReservationRepositoryAdapter implements ReservationRepository {

    private final SpringDataReservationRepository repository;

    ReservationRepositoryAdapter(SpringDataReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Reservation> findByOrderId(String orderId) {
        return repository.findByOrderId(orderId).map(ReservationEntity::toDomain);
    }

    /**
     * Kimliği olmayan bir ayırma yeni kayıttır; olan ise durum değişikliğidir
     * (ACTIVE → RELEASED). İkisini ayırmak, güncellemede kalemleri yeniden yazmayı
     * önler — kalemler ayırma boyunca değişmez.
     */
    @Override
    public Reservation save(Reservation reservation) {
        if (reservation.id() == null) {
            ReservationEntity saved = repository.save(
                    new ReservationEntity(UUID.randomUUID(), reservation));
            return saved.toDomain();
        }

        ReservationEntity existing = repository.findById(UUID.fromString(reservation.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Güncellenecek ayırma bulunamadı: " + reservation.id()));
        existing.apply(reservation);
        return existing.toDomain();
    }
}
