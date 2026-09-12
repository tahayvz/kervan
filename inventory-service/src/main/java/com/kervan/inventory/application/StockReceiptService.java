package com.kervan.inventory.application;

import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.domain.model.StockReceipt;
import com.kervan.inventory.domain.port.StockReceiptRepository;
import com.kervan.inventory.domain.port.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Mal kabulü: stoğun sisteme <b>girdiği</b> tek yol (ADR-0019).
 *
 * <h2>Neden vardı da yoktu</h2>
 * Bu servis açılana kadar stok yalnızca düşebiliyordu — ayırma ve geri bırakma vardı,
 * girişi yoktu. Sonucu tohumlama betiğinde görünüyordu: betik {@code psql} ile doğrudan
 * bu servisin veritabanına yazıyordu, yani başka bir servisin verisine dışarıdan
 * dokunuluyordu.
 *
 * <h2>İdempotentlik</h2>
 * HTTP isteği de Kafka mesajı gibi tekrarlanabilir: istemci zaman aşımı alır ve yeniden
 * dener, oysa ilk istek işlenmiştir. Tekrarı durduran şey istemcinin verdiği
 * {@code receiptId}; makbuz tablosunun birincil anahtarı odur.
 *
 * <p>Kontrol "önce sorgula, sonra yaz" diye yapılmıyor. O ikilinin arasına ikinci bir
 * istek girebilir ve ikisi de "yeni makbuz" sonucuna varabilirdi. Karar tek bir
 * {@code INSERT ... ON CONFLICT DO NOTHING} ifadesine bırakılıyor: yazan kazanır,
 * diğerine sıfır döner.
 *
 * <p>Aynı desen {@code StockReservationService}'te de var ({@code reservations.order_id}
 * benzersizliği) ve aynı gerekçeyle: ayrı bir "işlenmiş istekler" tablosu tutmak,
 * senkron kalması ve temizlenmesi gereken ikinci bir şey yaratırdı.
 *
 * <h2>Olay yayınlamıyor — bilerek</h2>
 * Outbox düzeneği bu serviste zaten var ve "stok geldi" olayını yazmak kolay olurdu.
 * Yazılmadı çünkü <b>tüketicisi yok</b>. Kimsenin dinlemediği bir olay, sürümlenmesi
 * ve uyumluluğu korunması gereken bir sözleşme yaratır ve karşılığında hiçbir şey
 * vermez. İlk tüketici çıktığında eklenir.
 */
@Service
public class StockReceiptService {

    private static final Logger log = LoggerFactory.getLogger(StockReceiptService.class);

    private final StockRepository stockRepository;
    private final StockReceiptRepository receiptRepository;
    private final Clock clock;

    public StockReceiptService(StockRepository stockRepository,
                               StockReceiptRepository receiptRepository,
                               Clock clock) {
        this.stockRepository = stockRepository;
        this.receiptRepository = receiptRepository;
        this.clock = clock;
    }

    /**
     * Mal kabulünü işler ve stoğun yeni hâlini döndürür.
     *
     * <p>Tekrar gelen bir makbuzda miktar <b>eklenmez</b>; mevcut stok olduğu gibi
     * döner. Çağıran için sonuç aynıdır — idempotent bir ucun vermesi gereken cevap da
     * budur: "istediğin durum sağlandı", "bunu daha önce de söylemiştin" değil.
     */
    @Transactional
    public StockItem receive(String receiptId, String sku, int quantity) {
        Instant now = clock.instant();

        // Satır önce açılıyor: mal kabulü hiç görülmemiş bir SKU için de gelebilir ve
        // aşağıdaki kilit yalnızca VAR OLAN satırı kilitler.
        stockRepository.createIfAbsent(sku);

        boolean isNew = receiptRepository.saveIfNew(new StockReceipt(receiptId, sku, quantity, now));
        if (!isNew) {
            // Yazma yok, dolayısıyla KİLİT DE YOK. Bu yol satırı kilitleseydi,
            // tekrarlanan (yani hiçbir şey yapmayan) bir istek aynı SKU'ya gelen
            // siparişleri bekletirdi. Bir yeniden denemenin bedeli, ilk isteğin
            // bedelinden yüksek olmamalı.
            log.debug("Makbuz zaten işlenmiş, miktar eklenmedi: receiptId={}", receiptId);
            return readStock(sku);
        }

        StockItem updated = lockStock(sku).receive(quantity);
        stockRepository.saveAll(List.of(updated));

        log.info("Mal kabulü: receiptId={} sku={} miktar={} yeniStok={}",
                receiptId, sku, quantity, updated.available());
        return updated;
    }

    /** Stok durumunu okur. Kilit almaz; bkz. {@code StockRepository.find}. */
    @Transactional(readOnly = true)
    public Optional<StockItem> find(String sku) {
        return stockRepository.find(sku);
    }

    /** Yazma öncesi: satırı kilitleyerek okur. */
    private StockItem lockStock(String sku) {
        return required(sku, stockRepository.lockAll(List.of(sku)).stream().findFirst());
    }

    /** Yazma olmayacak: kilit almadan okur. */
    private StockItem readStock(String sku) {
        return required(sku, stockRepository.find(sku));
    }

    private StockItem required(String sku, Optional<StockItem> found) {
        // createIfAbsent'tan sonra satırın var olması garanti. Buraya düşmek satırın
        // silindiği anlamına gelir; sessizce sıfır varsaymak yanlış cevabı doğru gibi
        // sunardı.
        return found.orElseThrow(() -> new IllegalStateException(
                "Stok satırı açıldıktan sonra bulunamadı: " + sku));
    }
}
