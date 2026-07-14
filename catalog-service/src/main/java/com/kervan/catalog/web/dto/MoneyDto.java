package com.kervan.catalog.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Para birimi DTO'su (web sınırı). Domain {@code Money}'den ayrıdır; HTTP sözleşmesi
 * ile domain modeli bağımsız evrilebilsin diye.
 */
public record MoneyDto(
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}
