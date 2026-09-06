package com.kervan.order.infrastructure.messaging;

import com.kervan.contracts.order.v1.OrderItem;
import com.kervan.order.domain.event.OrderPlaced;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Alan adamlı domain olayını, şemadan üretilen Avro kaydına çevirir.
 *
 * <p><b>Neden iki ayrı tip?</b> Domain olayı servisin kendi dili, Avro kaydı ise
 * dışarıyla yapılan sözleşmedir. Domain sınıfını doğrudan Avro'dan üretseydik,
 * iç modeldeki her yeniden adlandırma dışarıdaki sözleşmeyi de kırardı. Aradaki bu
 * çeviri sınıfı, iki tarafın ayrı hızda değişebilmesini sağlar.
 */
final class OrderPlacedAvroMapper {

    /** Şemadaki {@code decimal} alanların ölçeği; para alanları bu ölçeğe getirilir. */
    private static final int MONEY_SCALE = 2;

    private OrderPlacedAvroMapper() {
    }

    static com.kervan.contracts.order.v1.OrderPlaced toAvro(OrderPlaced event) {
        List<OrderItem> items = event.items().stream()
                .map(item -> OrderItem.newBuilder()
                        .setProductId(item.productId())
                        .setSku(item.sku())
                        .setQuantity(item.quantity())
                        .setUnitPrice(scaled(item.unitPrice()))
                        .build())
                .toList();

        return com.kervan.contracts.order.v1.OrderPlaced.newBuilder()
                .setOrderId(event.orderId())
                .setCustomerId(event.customerId())
                .setTotalAmount(scaled(event.totalAmount()))
                .setCurrency(event.currency())
                .setItems(items)
                .setPlacedAt(event.placedAt())
                .build();
    }

    /**
     * Avro'nun {@code decimal} tipi ölçeği şemada sabittir. Ölçeği farklı bir
     * {@link BigDecimal} verilirse Avro serileştirme sırasında hata verir; bu yüzden
     * ölçek burada açıkça ayarlanır.
     *
     * <p>{@code UNNECESSARY} bilinçli seçimdir: kuruşun altında bir kalıntı varsa
     * bu sessizce yuvarlanacak bir durum değil, tutarın yanlış hesaplandığının
     * işaretidir ve hata vermelidir.
     */
    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
