package com.kervan.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Mal kabul isteği.
 *
 * @param receiptId makbuz kimliği. <b>İstemci verir</b>, sunucu üretmez: tekrarı
 *     durduran şey budur. Sunucu üretseydi her yeniden deneme yeni bir makbuz olur ve
 *     miktar iki kez eklenirdi. İrsaliye numarası, sipariş numarası ya da istemcinin
 *     ürettiği bir UUID olabilir.
 * @param quantity gelen miktar. Pozitif olmalı; eksiltme mal kabulü değildir.
 */
public record ReceiveStockRequest(
        @NotBlank @Size(max = 64) String receiptId,
        @Positive int quantity) {
}
