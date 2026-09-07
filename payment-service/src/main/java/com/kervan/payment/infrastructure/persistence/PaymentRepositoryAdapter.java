package com.kervan.payment.infrastructure.persistence;

import com.kervan.payment.domain.model.Payment;
import com.kervan.payment.domain.port.PaymentRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class PaymentRepositoryAdapter implements PaymentRepository {

    private final SpringDataPaymentRepository repository;

    PaymentRepositoryAdapter(SpringDataPaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Payment> findByOrderId(String orderId) {
        return repository.findByOrderId(orderId).map(PaymentEntity::toDomain);
    }

    /** Kimliği olmayan ödeme yeni kayıttır; olan ise durum değişikliğidir. */
    @Override
    public Payment save(Payment payment) {
        if (payment.id() == null) {
            return repository.save(new PaymentEntity(UUID.randomUUID(), payment)).toDomain();
        }
        PaymentEntity existing = repository.findById(UUID.fromString(payment.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Güncellenecek ödeme bulunamadı: " + payment.id()));
        existing.apply(payment);
        return existing.toDomain();
    }
}
