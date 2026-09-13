package com.kervan.inventory.web;

import com.kervan.inventory.application.StockAdjustmentService;
import com.kervan.inventory.application.StockReceiptService;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.AdjustmentPageResponse;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import com.kervan.inventory.web.dto.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Stok REST API'si. İnce bir katman: HTTP ↔ application çevirisi yapar, iş kuralı içermez.
 *
 * <p>Bu servisin <b>ilk</b> HTTP ucu. Öncesinde stok yalnızca Kafka komutlarıyla
 * düşüyordu; girişi yoktu (ADR-0019).
 */
@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Stock", description = "Stok görüntüleme ve mal kabulü")
public class StockController {

    private final StockReceiptService service;
    private final StockAdjustmentService adjustments;

    public StockController(StockReceiptService service, StockAdjustmentService adjustments) {
        this.service = service;
        this.adjustments = adjustments;
    }

    /**
     * Mal kabulü.
     *
     * <p><b>200 döner, 201 değil.</b> Oluşturulan şey makbuz gibi görünse de isteğin
     * cevabı stoğun yeni hâlidir ve asıl soru "kaynak yaratıldı mı" değil, "stok ne
     * oldu". Tekrar gelen bir makbuz da aynı 200'ü ve aynı gövdeyi alır: idempotent bir
     * ucun cevabı "istediğin durum sağlandı" olmalıdır, "bunu daha önce de söylemiştin"
     * değil.
     */
    @PostMapping("/{sku}/receipts")
    @Operation(summary = "Mal kabulü: gelen miktarı stoğa ekler (aynı receiptId ile idempotent)")
    public StockResponse receive(@PathVariable String sku,
                                 @Valid @RequestBody ReceiveStockRequest req) {
        StockItem updated = service.receive(req.receiptId(), sku, req.quantity());
        return StockResponse.from(updated);
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Stok durumunu getir")
    public ResponseEntity<StockResponse> get(@PathVariable String sku) {
        return service.find(sku)
                .map(StockResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Sayım düzeltmesi (ADR-0021).
     *
     * <p>Mal kabulünden ayrı bir uç, çünkü ayrı bir şey: kabul bir olayı kaydeder,
     * düzeltme bir iddiayı. Gerekçe zorunlu ve kim yaptığı kaydedilir.
     *
     * <p><b>Kimlik istekten okunmuyor, token'dan geliyor.</b> Gövdeye bir "kim" alanı
     * koymak, denetim izini istemcinin doldurduğu bir alana bağlamak olurdu — yani
     * isteyenin başkasının adına düzeltme yazabilmesi demek. {@code Principal}'ın adı
     * JWT'nin {@code sub} alanıdır (bkz. JwtRoleConverter).
     */
    @PostMapping("/{sku}/adjustments")
    @Operation(summary = "Sayım düzeltmesi: küçükse uygulanır (200), büyükse onay bekler (202)")
    public ResponseEntity<StockResponse> adjust(@PathVariable String sku,
                                                @Valid @RequestBody AdjustStockRequest req,
                                                Principal caller) {
        StockItem stock = adjustments.adjust(
                req.adjustmentId(), sku, req.delta(), req.reason(), req.note(), caller.getName());

        // 202, 200 DEĞİL.
        //
        // Eşiğin üstündeki bir düzeltmede stok DEĞİŞMEDİ; kayıt onay bekliyor
        // (ADR-0022). 200 dönmek "yaptım" demek olurdu ve istemci stoğu değişmiş
        // sanardı. 202 Accepted tam olarak "isteğini aldım, henüz uygulamadım" der.
        //
        // Gövde yine güncel stok: değişmediğini görmek, istemcinin beklediği bilgi.
        return adjustments.isPending(req.adjustmentId())
                ? ResponseEntity.accepted().body(StockResponse.from(stock))
                : ResponseEntity.ok(StockResponse.from(stock));
    }

    /**
     * Bekleyen bir düzeltmeyi onaylar; stok o anda değişir (ADR-0022).
     *
     * <p>Yol SKU altında değil: onaylayan kişi elinde bir düzeltme kimliği tutar, o
     * kimliğin hangi SKU'ya ait olduğunu bilmek zorunda değildir. SKU'yu yola koymak,
     * onaylayanın onu yanlış yazması hâlinde sessizce 404 üretirdi.
     */
    @PostMapping("/adjustments/{adjustmentId}/approve")
    @Operation(summary = "Bekleyen düzeltmeyi onayla (isteyen kendi isteğini onaylayamaz)")
    public StockResponse approve(@PathVariable String adjustmentId, Principal caller) {
        return StockResponse.from(adjustments.approve(adjustmentId, caller.getName()));
    }

    /** Bekleyen bir düzeltmeyi reddeder. Stok hiç değişmez. */
    @PostMapping("/adjustments/{adjustmentId}/reject")
    @Operation(summary = "Bekleyen düzeltmeyi reddet")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable String adjustmentId, Principal caller) {
        adjustments.reject(adjustmentId, caller.getName());
    }

    /**
     * Düzeltme geçmişi — denetim izi.
     *
     * <p>Okunamayan bir denetim izi, denetim izi değildir. Sayfalama anahtar tabanlı:
     * {@code nextCursor} boş gelene kadar aynı değeri {@code ?cursor=} ile geri gönder.
     *
     * <p>İlk hâlinde yalnızca son 50 kayıt dönüyordu ve eskisine erişmenin yolu yoktu —
     * yani iz belli bir noktadan sonra okunamıyordu.
     */
    @GetMapping("/{sku}/adjustments")
    @Operation(summary = "Bir SKU'nun düzeltme geçmişi (en yeniden eskiye, sayfalı)")
    public AdjustmentPageResponse history(@PathVariable String sku,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer size) {
        return AdjustmentPageResponse.from(adjustments.history(sku, cursor, size));
    }
}
