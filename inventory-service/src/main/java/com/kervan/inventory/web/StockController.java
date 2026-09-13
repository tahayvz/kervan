package com.kervan.inventory.web;

import com.kervan.inventory.application.StockAdjustmentService;
import com.kervan.inventory.application.StockReceiptService;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.inventory.web.dto.AdjustStockRequest;
import com.kervan.inventory.web.dto.ReceiveStockRequest;
import com.kervan.inventory.web.dto.StockAdjustmentResponse;
import com.kervan.inventory.web.dto.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

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
    @Operation(summary = "Sayım düzeltmesi: stoğu artırır ya da azaltır (gerekçe zorunlu)")
    public StockResponse adjust(@PathVariable String sku,
                                @Valid @RequestBody AdjustStockRequest req,
                                Principal caller) {
        StockItem updated = adjustments.adjust(
                req.adjustmentId(), sku, req.delta(), req.reason(), req.note(), caller.getName());
        return StockResponse.from(updated);
    }

    /**
     * Düzeltme geçmişi — denetim izi.
     *
     * <p>Okunamayan bir denetim izi, denetim izi değildir. En yeniden eskiye, en fazla
     * {@link StockAdjustmentService#HISTORY_LIMIT} kayıt.
     */
    @GetMapping("/{sku}/adjustments")
    @Operation(summary = "Bir SKU'nun düzeltme geçmişi (en yeniden eskiye)")
    public List<StockAdjustmentResponse> history(@PathVariable String sku) {
        return adjustments.history(sku).stream().map(StockAdjustmentResponse::from).toList();
    }
}
