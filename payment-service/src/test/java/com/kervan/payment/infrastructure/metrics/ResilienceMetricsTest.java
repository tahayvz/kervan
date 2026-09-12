package com.kervan.payment.infrastructure.metrics;

import com.kervan.payment.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dayanıklılık durumunun ÖLÇÜLEBİLİR olduğunu doğrular.
 *
 * <h2>Neden önemli?</h2>
 * Devre kesici sessiz bir bileşendir: açıldığında hiçbir istisna yukarı çıkmaz,
 * istekler sadece hızlıca geri döner. Ölçülmezse, bir sağlayıcı arızası
 * "sistem yavaşlamadı ama siparişler düştü" şeklinde görünür ve sebebi
 * anlaşılmaz.
 *
 * <p>Ölçer adı ve <b>örnek adı</b> panonun sorgusuyla sözleşmedir. Örnek adı
 * ayarda yazılı ({@code payment-provider}); kayarsa pano boş kalır ve hiçbir yer
 * hata vermez.
 */
@AutoConfigureObservability
@DisplayName("Dayanıklılık metrikleri")
class ResilienceMetricsTest extends AbstractIntegrationTest {

    /** Ayardaki örnek adı. Prometheus'ta etiket olarak görünür: name="payment-provider". */
    private static final String INSTANCE = "payment-provider";

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("devre kesici durumu yayınlanıyor")
    void publishesCircuitBreakerState() {
        // Prometheus'taki karsiligi: resilience4j_circuitbreaker_state{name="payment-provider"}
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", INSTANCE).meters())
                .withFailMessage("devre kesici metrigi yok ya da ornek adi degismis")
                .isNotEmpty();
    }

    @Test
    @DisplayName("tekrar deneme ve eş zamanlılık sınırı da ölçülüyor")
    void publishesRetryAndBulkheadMetrics() {
        // Tekrar denemeler: "basarili ama tekrar denenerek" sayisi yukseliyorsa
        // saglayici bozulmaya baslamistir -- henuz hicbir siparis dusmeden once
        // gorulen erken uyaridir.
        assertThat(meters.find("resilience4j.retry.calls").tag("name", INSTANCE).meters())
                .withFailMessage("tekrar deneme metrigi yok")
                .isNotEmpty();

        // Kalan es zamanli cagri hakki sifira yaklasiyorsa saglayici yavasliyor.
        assertThat(meters.find("resilience4j.bulkhead.available.concurrent.calls")
                .tag("name", INSTANCE).meters())
                .withFailMessage("es zamanlilik siniri metrigi yok")
                .isNotEmpty();
    }
}
