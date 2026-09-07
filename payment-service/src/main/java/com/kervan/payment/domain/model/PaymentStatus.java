package com.kervan.payment.domain.model;

/** Bir ödemenin yaşam döngüsü. */
public enum PaymentStatus {
    /** Tutar tahsil edildi. */
    CAPTURED,
    /** Tahsil edilen tutar iade edildi (Saga telafisi). Buradan geri dönüş yoktur. */
    REFUNDED
}
