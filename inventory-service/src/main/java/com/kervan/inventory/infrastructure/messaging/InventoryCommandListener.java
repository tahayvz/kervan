package com.kervan.inventory.infrastructure.messaging;

import com.kervan.contracts.inventory.v1.ReleaseStock;
import com.kervan.contracts.inventory.v1.ReservationItem;
import com.kervan.contracts.inventory.v1.ReserveStock;
import com.kervan.inventory.application.StockReservationService;
import com.kervan.inventory.domain.model.ReservationLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Saga'nın stok komutlarını dinler.
 *
 * <h2>Neden sınıf düzeyinde {@code @KafkaListener}?</h2>
 * Bu konuda birden fazla mesaj tipi var ({@code ReserveStock}, {@code ReleaseStock});
 * aynı konuda durmalarının sebebi sıra garantisidir (ADR-0009). Sınıf düzeyinde
 * dinleyici + {@code @KafkaHandler}, gelen mesajın tipine göre doğru metoda
 * yönlendirir; {@code instanceof} zinciri yazmak gerekmez.
 *
 * <h2>Hata olursa</h2>
 * Metot bir istisna fırlatırsa offset ilerlemez ve mesaj tekrar gelir. İş katmanı
 * idempotent olduğu için tekrar zararsızdır: aynı komut ikinci kez işlenmez.
 */
@Component
// groupId burada verilmez: tek kaynak spring.kafka.consumer.group-id. İki yerde
// yazılsaydı biri değişip diğeri unutulabilirdi.
@KafkaListener(topics = "${kervan.inventory.commands-topic}")
class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

    private final StockReservationService reservations;

    InventoryCommandListener(StockReservationService reservations) {
        this.reservations = reservations;
    }

    @KafkaHandler
    void on(ReserveStock command) {
        log.debug("ReserveStock alındı: orderId={}", command.getOrderId());
        reservations.reserve(command.getOrderId(), toLines(command.getItems()));
    }

    @KafkaHandler
    void on(ReleaseStock command) {
        log.debug("ReleaseStock alındı: orderId={}", command.getOrderId());
        reservations.release(command.getOrderId());
    }

    /**
     * Tanınmayan bir komut tipi geldiğinde mesaj <b>kaybedilmez</b>.
     *
     * <p>İlk yazdığımda burada yalnızca uyarı loglanıp mesaj atlanıyordu. Sorun şu:
     * bir komut sessizce düşerse saga cevap beklerken asılı kalır ve bunu kimse fark
     * etmez — log satırlarına bakan olmaz.
     *
     * <p>İstisna fırlatılıyor; ölü mektup düzeneği mesajı {@code <konu>.DLT}'ye taşıyor
     * ve akış devam ediyor. Bu istisna <b>yeniden denenmez</b>: beklemekle tanınır hâle
     * gelmez, denemek yalnızca partition'ı geciktirir.
     */
    @KafkaHandler(isDefault = true)
    void onUnknown(Object message) {
        log.warn("Tanınmayan komut tipi ölü mektup konusuna gidiyor: {}",
                message.getClass().getName());
        throw new UnsupportedCommandException(message);
    }

    private static List<ReservationLine> toLines(List<ReservationItem> items) {
        return items.stream()
                .map(item -> new ReservationLine(item.getSku(), item.getQuantity()))
                .toList();
    }
}
