package com.kervan.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saf domain unit testleri — Spring/Mongo YOK, milisaniyede çalışır.
 * Domain'i framework'ten ayırmanın (hexagonal) somut faydası: iş kuralları
 * altyapı olmadan test edilebilir.
 */
class ProductTest {

    private static Product newPhone() {
        return Product.create("PHN-1", "Telefon", "açıklama", "Kervan",
                "elektronik/telefon", Money.of(new BigDecimal("100.00"), "TRY"),
                Map.of("renk", "siyah"));
    }

    @Test
    void create_startsInDraft_withTimestamps() {
        Product p = newPhone();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(p.getCreatedAt()).isNotNull();
        assertThat(p.getUpdatedAt()).isNotNull();
        assertThat(p.getId()).isNull(); // henüz kaydedilmedi
    }

    @Test
    void activate_setsStatusActive() {
        Product p = newPhone();
        p.activate();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void archive_setsStatusArchived() {
        Product p = newPhone();
        p.archive();
        assertThat(p.getStatus()).isEqualTo(ProductStatus.ARCHIVED);
    }

    @Test
    void create_withBlankSku_isRejected() {
        assertThatThrownBy(() -> Product.create("  ", "Telefon", null, "Kervan",
                "elektronik", Money.of(BigDecimal.TEN, "TRY"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void money_withNegativeAmount_isRejected() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1"), "TRY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void money_withInvalidCurrency_isRejected() {
        assertThatThrownBy(() -> Money.of(BigDecimal.TEN, "XYZ123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAttributes_returnsImmutableCopy() {
        Product p = newPhone();
        assertThatThrownBy(() -> p.getAttributes().put("hack", "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
