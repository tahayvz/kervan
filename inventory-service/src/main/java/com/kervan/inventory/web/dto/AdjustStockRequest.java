package com.kervan.inventory.web.dto;

import com.kervan.inventory.domain.model.AdjustmentReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Stok düzeltme isteği.
 *
 * @param adjustmentId düzeltme kimliği. <b>İstemci verir</b>; tekrarı durduran şey budur.
 * @param delta pozitif ya da negatif. Sıfır kabul edilmez — hiçbir şey değiştirmeyen
 *     bir düzeltme yalnızca denetim izini kirletir. Sıfır denetimi alan modelinde.
 * @param reason gerekçe. <b>Zorunlu</b> ve sınırlı bir listeden; serbest metin değil.
 * @param note açıklama. {@code OTHER} gerekçesinde zorunlu, diğerlerinde isteğe bağlı.
 */
public record AdjustStockRequest(
        @NotBlank @Size(max = 64) String adjustmentId,
        int delta,
        @NotNull AdjustmentReason reason,
        @Size(max = 500) String note) {
}
