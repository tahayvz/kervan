package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.StockAdjustment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Denetim izi sayfalamasının işareti (cursor).
 *
 * <p>İçeriği son kaydın {@code adjustedAt} ve {@code adjustmentId} değerleri; ikisi
 * birlikte sıralamadaki tam yeri gösterir.
 *
 * <p><b>Neden kodlanıyor?</b> İstemci için opak olsun diye. İki alan açıkça
 * verilseydi istemci onları kendi üretmeye başlar, sayfalama yöntemi bir gün
 * değiştiğinde de kırılırdı. Opak bir işaret, "bunu yorumlama, aynen geri gönder"
 * demenin yoludur.
 *
 * <p>Base64 bir <b>güvenlik önlemi değildir</b> ve öyle sunulmuyor: kolayca çözülür.
 * Amaç gizlemek değil, istemciyi biçime bağlanmaktan caydırmak.
 */
final class AuditCursor {

    private static final String SEPARATOR = "|";

    private AuditCursor() {
    }

    static String of(StockAdjustment last) {
        String raw = last.adjustedAt().toString() + SEPARATOR + last.adjustmentId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * İşareti çözer.
     *
     * @throws IllegalArgumentException işaret bozuksa. Sessizce ilk sayfaya dönmek
     *     yanlış olurdu: okuyan kişi sayfa çevirdiğini sanırken başa döner ve aynı
     *     kayıtları ikinci kez okur.
     */
    static Decoded decode(String cursor) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Geçersiz sayfa işareti", e);
        }
        int separator = raw.lastIndexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("Geçersiz sayfa işareti");
        }
        try {
            return new Decoded(Instant.parse(raw.substring(0, separator)),
                    raw.substring(separator + SEPARATOR.length()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Geçersiz sayfa işareti", e);
        }
    }

    record Decoded(Instant adjustedAt, String adjustmentId) {
    }
}
