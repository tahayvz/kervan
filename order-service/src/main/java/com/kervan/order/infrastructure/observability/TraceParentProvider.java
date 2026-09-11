package com.kervan.order.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * O anda açık olan izin (trace) kimliğini W3C {@code traceparent} metnine çevirir.
 *
 * <h2>Neden elle taşınıyor?</h2>
 * Servisler arası çağrılarda izleme bağlamını kütüphaneler kendisi taşır: HTTP
 * isteğine bir başlık eklenir, karşı taraf onu okur. Outbox'ta bu zincir kopar.
 * Olay Kafka'ya bu iş parçacığından gitmez; önce tabloya yazılır, sonra Debezium
 * onu veritabanının değişiklik günlüğünden okuyup yayınlar. Debezium'un ne isteği
 * ne de iş parçacığı vardır; taşıyacak bağlamı bilemez.
 *
 * <p>Çözüm, bağlamı <b>verinin yanına</b> koymaktır: satırda bir sütun olarak. Debezium
 * o sütunu Kafka başlığına kopyalar, tüketen servis başlığı okuyup aynı ize devam eder.
 * Sonuçta Jaeger'da tek bir zincir görünür: sipariş isteği → stok → ödeme → tamamlanma.
 * Karar kaydı: {@code docs/adr/0013-trace-context-across-outbox.md}
 *
 * <h2>Biçim</h2>
 * {@code 00-<32 haneli trace id>-<16 haneli span id>-<01 veya 00>}. Son alan
 * "bu iz örneklendi mi" demektir; örneklenmemiş bir izi tüketici de kaydetmez,
 * böylece karar zincirin tamamında aynı kalır.
 *
 * <h2>Yokluğu hata değildir</h2>
 * İzleme kapalıysa ya da çağıran kod bir istek bağlamında değilse {@code null} döner.
 * Olay yine yazılır ve yayınlanır; yalnızca zincir o noktadan yeniden başlar. İzleme
 * teşhis aracıdır, iş akışının ön koşulu değil.
 */
@Component
public class TraceParentProvider {

    /** W3C başlık adı. Debezium sütunu bu adla Kafka başlığına koyar. */
    public static final String HEADER = "traceparent";

    private static final String VERSION = "00";
    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;

    /**
     * {@code ObjectProvider}: izleme bağımlılığı olmayan bir test bağlamında
     * {@code Tracer} bean'i bulunmayabilir. Zorunlu bağımlılık olsaydı o bağlamlar
     * ayağa kalkmazdı — izleme yüzünden uygulamanın açılmaması kabul edilemez.
     */
    private final ObjectProvider<Tracer> tracer;

    TraceParentProvider(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /** @return o anki iz için {@code traceparent} metni; iz yoksa {@code null}. */
    public String current() {
        Tracer available = tracer.getIfAvailable();
        if (available == null) {
            return null;
        }
        Span span = available.currentSpan();
        return span == null ? null : format(span.context());
    }

    /**
     * Kimlikler sola sıfırla doldurulur. OpenTelemetry zaten tam uzunlukta üretir
     * ama W3C biçimi sabit uzunluk şart koşar; kısa bir kimlik gönderilirse karşı
     * taraf başlığı geçersiz sayıp atar ve zincir sessizce kopardı.
     */
    static String format(TraceContext context) {
        if (context == null || context.traceId() == null || context.spanId() == null) {
            return null;
        }
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return VERSION + "-" + pad(context.traceId(), TRACE_ID_LENGTH)
                + "-" + pad(context.spanId(), SPAN_ID_LENGTH)
                + "-" + flags;
    }

    private static String pad(String id, int length) {
        return id.length() >= length ? id : "0".repeat(length - id.length()) + id;
    }
}
