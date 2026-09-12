package com.kervan.inventory.infrastructure.messaging;

import com.kervan.inventory.domain.port.InventoryEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stok sonuçlarını sayar.
 *
 * <h2>Neden sarmalayıcı (decorator)?</h2>
 * Sayacı {@code StockReservationService}'in içine koymak, uygulama katmanına
 * "ölçülüyorum" bilgisini sokardı. Bu depoda aynı sorun önbellekte de çıkmıştı ve
 * aynı şekilde çözülmüştü ({@code CachingProductRepository}): davranışı değiştirmeyen
 * bir katman, işi yapan sınıfın etrafına sarılır.
 *
 * <p>Ölçüm noktası olarak olay yayını seçildi, çünkü <b>gerçekten olan</b> şey budur.
 * Metodun çağrılması değil, olayın yazılması sonucu belirler: aynı komut ikinci kez
 * geldiğinde servis erken çıkar ve hiçbir olay yazılmaz — sayaç da artmaz. Doğrusu
 * budur; yoksa tekrar teslimatlar gerçek stok hareketi gibi görünürdü.
 *
 * <h2>Ne cevaplıyor?</h2>
 * "Ne sıklıkta stok yetmiyor?" Bu teknik değil <b>ticari</b> bir sinyaldir: oran
 * yükseliyorsa ya stok planlaması bozuktur ya da bir ürün beklenmedik ilgi görüyordur.
 * Hata log'una düşmez, çünkü bu bir hata değildir.
 */
@Component
@Primary
class MeteredInventoryEventPublisher implements InventoryEventPublisher {

    private static final String METRIC = "kervan.stock.reservations";

    private final InventoryEventPublisher delegate;
    private final Counter reserved;
    private final Counter rejected;
    private final Counter released;

    MeteredInventoryEventPublisher(AvroInventoryEventPublisher delegate, MeterRegistry registry) {
        this.delegate = delegate;

        // Etiket kümesi sabit ve üç elemanlı. Başarısızlık sebebini etiket yapmak
        // cazipti, ama sebep serbest metindir: her farklı metin yeni bir zaman
        // serisi açar ve depoyu şişirir. Sebep log'da durur, metrikte durmaz.
        this.reserved = counter(registry, "reserved");
        this.rejected = counter(registry, "rejected");
        this.released = counter(registry, "released");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder(METRIC)
                .description("Stok ayirma sonuclari")
                .tag("result", result)
                .register(registry);
    }

    @Override
    public void stockReserved(String orderId, String reservationId, Instant at) {
        delegate.stockReserved(orderId, reservationId, at);
        // Sayaç yayından SONRA artar: yayın patlarsa transaction geri alınır ve
        // hiçbir şey olmamış olur. Önce artsaydı sayaç olmamış bir olayı sayardı.
        reserved.increment();
    }

    @Override
    public void stockReservationFailed(String orderId, String reason, Instant at) {
        delegate.stockReservationFailed(orderId, reason, at);
        rejected.increment();
    }

    @Override
    public void stockReleased(String orderId, String reservationId, Instant at) {
        delegate.stockReleased(orderId, reservationId, at);
        released.increment();
    }
}
