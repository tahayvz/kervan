package com.kervan.order.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * İzleme bağlamının metne çevrilmesi.
 *
 * <p>Bu biçim bir <b>sözleşmedir</b>: değeri okuyan taraf bu kod değil, karşıdaki
 * servisin OpenTelemetry kütüphanesidir. Biçim bozulursa hata alınmaz — başlık
 * geçersiz sayılıp atılır ve zincir sessizce kopar. Sessiz bozulmalar ancak
 * testle yakalanır.
 *
 * <p>Aynı sınıfın kopyaları inventory ve payment servislerinde de var (outbox
 * düzeneğinin tamamı gibi; bedeli ADR-0004'te yazılı). Biçim kuralı burada
 * doğrulanıyor.
 */
@DisplayName("TraceParentProvider")
class TraceParentProviderTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("örneklenmiş iz 01 bayrağıyla yazılır")
    void formatsSampledContext() {
        assertThat(TraceParentProvider.format(context(TRACE_ID, SPAN_ID, true)))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    @DisplayName("örneklenmemiş iz 00 bayrağıyla yazılır — karar zincir boyunca korunur")
    void formatsUnsampledContext() {
        assertThat(TraceParentProvider.format(context(TRACE_ID, SPAN_ID, false)))
                .endsWith("-00");
    }

    @Test
    @DisplayName("karar verilmemişse örneklenmemiş sayılır")
    void treatsUnknownSamplingAsNotSampled() {
        // sampled() null dönebilir: henüz karar verilmemiş demektir. Bunu "evet"
        // saymak, örneklenmeyen istekleri karşı tarafta kaydettirirdi.
        assertThat(TraceParentProvider.format(context(TRACE_ID, SPAN_ID, null)))
                .endsWith("-00");
    }

    @Test
    @DisplayName("kısa kimlikler sola sıfırla doldurulur")
    void padsShortIdentifiers() {
        // OpenTelemetry tam uzunlukta üretir ama W3C biçimi sabit uzunluk şart
        // koşar. Doldurma olmasaydı, farklı bir izleme kütüphanesine geçildiğinde
        // (örneğin 64 bitlik kimlik üreten biri) başlık sessizce geçersiz olurdu.
        String formatted = TraceParentProvider.format(context("a3ce929d0e0e4736", "0ba902b7", true));

        assertThat(formatted).isEqualTo("00-0000000000000000a3ce929d0e0e4736-000000000ba902b7-01");
    }

    @Test
    @DisplayName("bağlam yoksa null döner; olay yine yayınlanır")
    void returnsNullWithoutContext() {
        assertThat(TraceParentProvider.format(null)).isNull();
    }

    @Test
    @DisplayName("açık span varsa değeri o span'den üretir")
    void readsCurrentSpan() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context(TRACE_ID, SPAN_ID, true));

        assertThat(providerWith(tracer).current())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
    }

    @Test
    @DisplayName("açık span yoksa null döner")
    void returnsNullWhenNoSpanIsActive() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        // Zamanlanmış bir işten çağrılmak bu duruma örnektir: taşınacak bağlam yok.
        assertThat(providerWith(tracer).current()).isNull();
    }

    @Test
    @DisplayName("izleme hiç kurulu değilse null döner — uygulama yine çalışır")
    void returnsNullWhenTracerBeanIsMissing() {
        // Tracer'ı zorunlu bağımlılık yapmak, izleme bağımlılığı olmayan bir test
        // bağlamının hiç ayağa kalkmaması demekti. İzleme yüzünden uygulamanın
        // açılmaması kabul edilemez.
        assertThat(providerWith(null).current()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static TraceParentProvider providerWith(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new TraceParentProvider(provider);
    }

    private static TraceContext context(String traceId, String spanId, Boolean sampled) {
        return new TraceContext() {
            @Override
            public String traceId() {
                return traceId;
            }

            @Override
            public String parentId() {
                return null;
            }

            @Override
            public String spanId() {
                return spanId;
            }

            @Override
            public Boolean sampled() {
                return sampled;
            }
        };
    }
}
