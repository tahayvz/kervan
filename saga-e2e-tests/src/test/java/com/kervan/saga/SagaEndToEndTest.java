package com.kervan.saga;

import com.kervan.inventory.application.StockReceiptService;
import com.kervan.inventory.domain.model.StockItem;
import com.kervan.order.application.OrderService;
import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.domain.model.Caller;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga'nın iki yolunu <b>üç gerçek servis</b> üzerinden yürütür.
 *
 * <p>Bugüne kadar her servis kendi yarısını, karşı tarafın elle yazılmış taklidine
 * karşı doğruluyordu. Burada taklit yok: order gerçekten komut yayınlıyor, inventory
 * onu gerçekten tüketip stoğu değiştiriyor, payment gerçekten karar veriyor ve order
 * cevabı gerçekten işleyip siparişi kapatıyor.
 *
 * <p>Tek istisna CDC taşıması; gerekçesi {@link OutboxBridge}'de.
 */
@DisplayName("Saga — üç servis birlikte")
class SagaEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static OrderService orders;
    private static StockReceiptService stock;
    private static com.kervan.inventory.domain.port.StockRepository stockRepository;

    @BeforeAll
    static void startEverything() {
        SagaEnvironment.start();
        orders = SagaEnvironment.order().getBean(OrderService.class);
        stock = SagaEnvironment.inventory().getBean(StockReceiptService.class);
        stockRepository = SagaEnvironment.inventory()
                .getBean(com.kervan.inventory.domain.port.StockRepository.class);
    }

    @Test
    @DisplayName("mutlu yol: stok ayrılır, ödeme alınır, sipariş ONAYLANIR")
    void happyPath() {
        String sku = seedStock(100);

        // Eşiğin ALTINDA: ödeme onaylanacak.
        String orderId = placeOrder(sku, 2, "10.0000");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED));

        // Stok gerçekten ayrılmış olmalı: sipariş onaylandı diye stoğun düştüğünü
        // varsaymak, tam da bu testin kapatmak istediği boşluk olurdu.
        StockItem item = stockOf(sku);
        assertThat(item.reserved()).isEqualTo(2);
        assertThat(item.available()).isEqualTo(98);
    }

    @Test
    @DisplayName("telafi: ödeme reddedilir, stok GERİ BIRAKILIR, sipariş İPTAL olur")
    void compensationPath() {
        String sku = seedStock(100);

        // Eşiğin ÜSTÜNDE: ödeme reddedilecek ve telafi zinciri çalışacak.
        String orderId = placeOrder(sku, 3, "5000.0000");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED));

        // Asıl iddia bu: ayrılan stok geri döndü. Sipariş iptal olup stok tutulu
        // kalsaydı, ürün kimseye satılamayan bir hayalete dönerdi — ve sipariş
        // tablosuna bakan hiç kimse bunu göremezdi.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            StockItem item = stockOf(sku);
            assertThat(item.reserved()).isZero();
            assertThat(item.available()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("stok yetmezse ödemeye hiç gidilmez, sipariş İPTAL olur")
    void insufficientStockPath() {
        String sku = seedStock(1);

        String orderId = placeOrder(sku, 5, "10.0000");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED));

        // Stok hiç değişmemeli: yetmeyen bir ayırma kısmen yapılmaz.
        StockItem item = stockOf(sku);
        assertThat(item.reserved()).isZero();
        assertThat(item.available()).isEqualTo(1);
    }

    // --- yardımcılar ---

    /** Stoğu inventory'nin KENDI mal kabul ucuyla açar (ADR-0019), SQL ile değil. */
    private String seedStock(int quantity) {
        String sku = "SKU-" + UUID.randomUUID();
        stock.receive("receipt-" + sku, sku, quantity);
        return sku;
    }

    private String placeOrder(String sku, int quantity, String unitPrice) {
        Order order = orders.placeOrder(
                new PlaceOrderCommand("TRY", List.of(new PlaceOrderCommand.Line(
                        "product-" + sku, sku, quantity, new BigDecimal(unitPrice)))),
                Caller.customer("musteri-1"));
        return order.id();
    }

    private OrderStatus statusOf(String orderId) {
        return orders.getOrder(orderId, Caller.admin("yonetici-1")).status();
    }

    private StockItem stockOf(String sku) {
        return stockRepository.find(sku).orElseThrow();
    }
}
