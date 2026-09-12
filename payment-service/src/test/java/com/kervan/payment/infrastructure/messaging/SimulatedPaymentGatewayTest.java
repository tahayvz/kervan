package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.PaymentDeclinedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Taklit sağlayıcının davranışı.
 *
 * <p>Taklit olması onu önemsiz yapmaz: tekrar deneme mantığının güvenliği bu
 * sınıfın idempotent olmasına dayanıyor. Gerçek bir sağlayıcıda karşılığı
 * "idempotency key"dir ve tam olarak aynı sebeple vardır.
 */
@DisplayName("SimulatedPaymentGateway")
class SimulatedPaymentGatewayTest {

    private static final BigDecimal LIMIT = new BigDecimal("10000.00");

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(LIMIT);

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("TRY"));
    }

    @Test
    @DisplayName("aynı sipariş ikinci kez tahsil edilmez, ilk kimlik döner")
    void isIdempotentPerOrder() {
        String first = gateway.charge("order-1", "c-1", money("100.00"));
        String second = gateway.charge("order-1", "c-1", money("100.00"));

        // Tekrar denemenin guvenli olmasinin dayandigi davranis budur: ag
        // koptugunda cagiran taraf tekrar dener ve musteriden ikinci kez para
        // cekilmez.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("farklı siparişler farklı işlem kimliği alır")
    void differentOrdersGetDifferentReferences() {
        assertThat(gateway.charge("order-1", "c-1", money("100.00")))
                .isNotEqualTo(gateway.charge("order-2", "c-1", money("100.00")));
    }

    @Test
    @DisplayName("sınırın üstü reddedilir — rastgele değil, kurallı")
    void declinesAboveTheConfiguredLimit() {
        // Kural tabanli olmasi bilincli: rastgele olsaydi saganin basarisizlik
        // yolu testte kararli calismazdi.
        assertThatThrownBy(() -> gateway.charge("order-1", "c-1", money("10000.01")))
                .isInstanceOf(PaymentDeclinedException.class);

        assertThat(gateway.charge("order-2", "c-1", money("10000.00"))).isNotBlank();
    }

    @Test
    @DisplayName("reddedilen sipariş kayda geçmez; sonradan tahsil edilebilir")
    void declinedOrderIsNotRemembered() {
        assertThatThrownBy(() -> gateway.charge("order-1", "c-1", money("20000.00")))
                .isInstanceOf(PaymentDeclinedException.class);

        // Reddedilme bir tahsilat DEGILDIR. Kaydedilseydi, tutari duzeltilip
        // tekrar gonderilen bir siparis "zaten tahsil edildi" sanilirdi.
        assertThat(gateway.charge("order-1", "c-1", money("100.00"))).startsWith("SIM-");
    }
}
