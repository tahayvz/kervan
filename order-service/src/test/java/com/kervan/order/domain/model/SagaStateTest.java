package com.kervan.order.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saga durum makinesinin izin verdiği ve vermediği geçişler.
 *
 * <p>Bu tablo tek yerde durduğu için testi de tek yerde: olaylar en az bir kez ve
 * bazen sırasız gelir, hangi geçişin geçerli olduğu koda dağılsaydı tekrar gelen bir
 * olay saga'yı geri sarabilir ya da iki kez ilerletebilirdi.
 */
@DisplayName("SagaState")
class SagaStateTest {

    @Test
    @DisplayName("mutlu yol: stok → ödeme → tamamlandı")
    void happyPath() {
        assertThat(SagaState.STOCK_RESERVING.canTransitionTo(SagaState.PAYMENT_PROCESSING)).isTrue();
        assertThat(SagaState.PAYMENT_PROCESSING.canTransitionTo(SagaState.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("stok yetmezse doğrudan iptal — geri alınacak bir şey yok")
    void stockFailureCancelsDirectly() {
        assertThat(SagaState.STOCK_RESERVING.canTransitionTo(SagaState.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("ödeme başarısızsa önce telafi, sonra iptal")
    void paymentFailureCompensatesFirst() {
        // Doğrudan iptale geçilemez: stok hâlâ tutuluyor. "İptal edildi" derken
        // stoğun tutuluyor olması, müşteriye satılmayan ürünü kilitlemek olurdu.
        assertThat(SagaState.PAYMENT_PROCESSING.canTransitionTo(SagaState.CANCELLED)).isFalse();
        assertThat(SagaState.PAYMENT_PROCESSING.canTransitionTo(SagaState.STOCK_RELEASING)).isTrue();
        assertThat(SagaState.STOCK_RELEASING.canTransitionTo(SagaState.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("bitmiş saga hiçbir yere gitmez")
    void finalStatesAreFinal() {
        assertThat(SagaState.COMPLETED.isFinal()).isTrue();
        assertThat(SagaState.CANCELLED.isFinal()).isTrue();

        for (SagaState next : SagaState.values()) {
            assertThat(SagaState.COMPLETED.canTransitionTo(next)).isFalse();
            assertThat(SagaState.CANCELLED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    @DisplayName("geri sarma yok: hiçbir durum kendisine ya da önceki adıma dönemez")
    void neverGoesBackwards() {
        // Tekrar gelen bir olay saga'yı geri sararsa aynı komut ikinci kez gönderilir.
        assertThat(SagaState.PAYMENT_PROCESSING.canTransitionTo(SagaState.STOCK_RESERVING)).isFalse();
        assertThat(SagaState.STOCK_RELEASING.canTransitionTo(SagaState.PAYMENT_PROCESSING)).isFalse();

        Arrays.stream(SagaState.values())
                .forEach(state -> assertThat(state.canTransitionTo(state)).isFalse());
    }
}
