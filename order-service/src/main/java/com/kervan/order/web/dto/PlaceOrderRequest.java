package com.kervan.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sipariş oluşturma isteği.
 * <p>
 * <b>customerId burada YOK.</b> Sipariş sahibi doğrulanmış token'dan alınır; istemcinin
 * bildirdiği bir alandan değil. Aksi hâlde geçerli bir token taşıyan herhangi biri,
 * gövdeye başka bir kimlik yazarak o kişinin adına sipariş oluşturabilirdi.
 */
public record PlaceOrderRequest(

        @NotBlank(message = "currency zorunludur")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency ISO 4217 kodu olmalı (örn. TRY)")
        String currency,

        @NotEmpty(message = "Sipariş en az bir satır içermeli")
        @Valid
        List<Line> lines) {

    public record Line(
            @NotBlank(message = "productId zorunludur")
            String productId,

            @NotBlank(message = "sku zorunludur")
            String sku,

            @Min(value = 1, message = "quantity en az 1 olmalı")
            int quantity,

            @NotNull(message = "unitPrice zorunludur")
            @DecimalMin(value = "0.0", message = "unitPrice negatif olamaz")
            BigDecimal unitPrice) {
    }
}
