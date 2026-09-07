package com.kervan.inventory.domain.model;

/** Bir ayırmanın yaşam döngüsü. */
public enum ReservationStatus {
    /** Stok tutuluyor; sipariş henüz onaylanmadı. */
    ACTIVE,
    /** Tutulan stok geri bırakıldı (Saga telafisi). Buradan geri dönüş yoktur. */
    RELEASED
}
