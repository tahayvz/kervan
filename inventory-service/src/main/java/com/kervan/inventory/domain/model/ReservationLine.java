package com.kervan.inventory.domain.model;

import java.util.Objects;

/** Ayırmanın tek bir kalemi. */
public record ReservationLine(String sku, int quantity) {

    public ReservationLine {
        Objects.requireNonNull(sku, "sku null olamaz");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity pozitif olmalı: " + quantity);
        }
    }
}
