package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.InsufficientStockException;
import com.kervan.inventory.domain.model.Reservation;
import com.kervan.inventory.domain.model.ReservationLine;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.port.InventoryEventPublisher;
import com.kervan.inventory.domain.port.ReservationRepository;
import com.kervan.inventory.domain.port.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saga'nın stok adımı.
 *
 * <h2>İdempotentlik</h2>
 * Kafka teslimatı <b>en az bir kez</b>'dir; aynı komut iki kez gelebilir. İkinci
 * gelişte stok tekrar düşmemelidir.
 *
 * <p>Bunun için ayrı bir "işlenmiş mesajlar" tablosu tutulmuyor. İşin kendisinde zaten
 * bir doğal anahtar var: <b>bir siparişin en fazla bir ayırması olur</b>. Bu kural
 * veritabanında benzersizlik kısıtı olarak duruyor. Aynı kuralı ikinci bir tabloda
 * tekrar tutmak, senkron kalması ve temizlenmesi gereken ikinci bir şey yaratırdı.
 *
 * <p>İki katman var: önce mevcut ayırma aranır (normal durum), yarış hâlinde
 * veritabanı kısıtı devreye girer (istisnai durum).
 *
 * <p><b>Aşağıdaki erken çıkış bir hızlandırma değildir.</b> Kaldırıldığında tekrar
 * gelen komut kısıta takılıp istisna fırlatır; dinleyici istisna aldığında offset
 * ilerlemez ve aynı mesaj sonsuza kadar tekrar gelir — o partition'daki arkadaki her
 * şeyle birlikte. Kısıt doğru sonucu verir ama sistemi kilitler. Denendi ve görüldü.
 *
 * <h2>Başarısızlık de bir sonuçtur</h2>
 * Stok yetmediğinde istisna <b>dışarı taşmaz</b>. Taşsaydı transaction geri alınır ve
 * onunla birlikte "stok yetmedi" olayı da silinirdi; saga cevap beklerken sonsuza
 * kadar asılı kalırdı. Bunun yerine stok hiç değiştirilmez, yalnızca başarısızlık
 * olayı yazılır — ikisi aynı commit'te.
 */
@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryEventPublisher events;
    private final Clock clock;

    public StockReservationService(StockRepository stockRepository,
                                   ReservationRepository reservationRepository,
                                   InventoryEventPublisher events,
                                   Clock clock) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.events = events;
        this.clock = clock;
    }

    /** Sipariş için stok ayırır; yetmezse başarısızlık olayı yazar. */
    @Transactional
    public void reserve(String orderId, List<ReservationLine> requested) {
        Instant now = clock.instant();

        if (reservationRepository.findByOrderId(orderId).isPresent()) {
            // Komut tekrar geldi. İlk işlemenin olayı zaten outbox'ta; tekrar
            // yazmak tüketiciye ikinci bir "ayrıldı" göndermek olurdu.
            log.debug("Ayırma zaten var, komut yok sayıldı: orderId={}", orderId);
            return;
        }

        Map<String, StockItem> locked = lockStockFor(requested);

        Map<String, StockItem> updated;
        try {
            updated = applyReservation(locked, requested);
        } catch (InsufficientStockException e) {
            // Stok değişmedi; yalnızca sonucu duyuruyoruz. Aynı transaction'da
            // yazıldığı için saga cevabı mutlaka alır.
            log.info("Stok yetersiz, sipariş iptal edilecek: orderId={} sku={}", orderId, e.sku());
            events.stockReservationFailed(orderId, e.getMessage(), now);
            return;
        }

        stockRepository.saveAll(updated.values());
        Reservation saved = reservationRepository.save(
                Reservation.active(orderId, requested, now));

        events.stockReserved(orderId, saved.id(), now);
        log.info("Stok ayrıldı: orderId={} reservationId={}", orderId, saved.id());
    }

    /** Telafi: daha önce ayrılmış stoğu geri bırakır. */
    @Transactional
    public void release(String orderId) {
        Instant now = clock.instant();

        Optional<Reservation> found = reservationRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            // Ayırma hiç yapılmamış (örneğin stok baştan yetmemişti). Geri bırakılacak
            // bir şey yok; telafi komutu bu durumda da gelebilir ve hata değildir.
            log.debug("Geri bırakılacak ayırma yok: orderId={}", orderId);
            return;
        }

        Reservation reservation = found.get();
        if (!reservation.isActive()) {
            // Telafi komutu ikinci kez geldi. Tekrar geri bırakmak aynı miktarı
            // satılabilire iki kez eklerdi — yoktan stok yaratmak.
            log.debug("Ayırma zaten geri bırakılmış: orderId={}", orderId);
            return;
        }

        Map<String, StockItem> locked = lockStockFor(reservation.lines());
        Map<String, StockItem> updated = new LinkedHashMap<>(locked);
        for (ReservationLine line : reservation.lines()) {
            updated.computeIfPresent(line.sku(), (sku, item) -> item.release(line.quantity()));
        }

        stockRepository.saveAll(updated.values());
        reservationRepository.save(reservation.released(now));

        events.stockReleased(orderId, reservation.id(), now);
        log.info("Stok geri bırakıldı: orderId={} reservationId={}", orderId, reservation.id());
    }

    private Map<String, StockItem> lockStockFor(List<ReservationLine> lines) {
        List<String> skus = lines.stream().map(ReservationLine::sku).distinct().toList();
        Map<String, StockItem> bySku = new LinkedHashMap<>();
        stockRepository.lockAll(skus).forEach(item -> bySku.put(item.sku(), item));
        return bySku;
    }

    /**
     * Ayırmayı bellekte uygular. Hiçbir şey kaydedilmez: kalemlerden biri bile
     * yetmezse hiçbiri ayrılmamalıdır ("ya hep ya hiç").
     */
    private Map<String, StockItem> applyReservation(Map<String, StockItem> stock,
                                                    List<ReservationLine> requested) {
        Map<String, StockItem> result = new LinkedHashMap<>(stock);
        for (ReservationLine line : requested) {
            StockItem item = result.get(line.sku());
            if (item == null) {
                // Katalogda olmayan ya da hiç stok kaydı açılmamış ürün. Bilinmeyen
                // bir SKU için 0 stok varsaymak, sessizce yanlış cevap vermek olurdu.
                throw new InsufficientStockException(line.sku(), line.quantity(), 0);
            }
            result.put(line.sku(), item.reserve(line.quantity()));
        }
        return result;
    }
}
