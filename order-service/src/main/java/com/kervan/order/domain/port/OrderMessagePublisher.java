package com.kervan.order.domain.port;

import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.OrderLine;

import java.time.Instant;
import java.util.List;

/**
 * Bu servisten çıkan bütün mesajlar.
 *
 * <p>İki tür var ve ayrım kasıtlıdır (ADR-0009):
 * <ul>
 *   <li><b>Olay</b> — olan bitenin duyurusu; kimin dinlediği bizim işimiz değil.
 *       {@code orderPlaced}, {@code orderConfirmed}, {@code orderCancelled}.</li>
 *   <li><b>Komut</b> — belirli bir servise verilen iş; reddedilebilir.
 *       {@code reserveStock}, {@code processPayment} ve telafileri.</li>
 * </ul>
 *
 * <p>Uygulama katmanı mesajın hangi biçimde serileştirildiğini ve hangi konuya
 * gittiğini bilmez. Genel bir {@code send(Object)} yerine niyet bildiren metotlar var:
 * hangi mesajların var olduğu tek bakışta görünür ve yanlış tipte bir nesne
 * gönderilemez.
 *
 * <p>Hepsi outbox'a yazar, doğrudan Kafka'ya değil — iş verisiyle aynı transaction'da
 * (ADR-0004). Bu, saga için özellikle önemli: durum değişikliği ile onu ilerleten
 * komut ya birlikte olur ya hiç olmaz. Aksi hâlde araya giren bir çökme saga'yı
 * sonsuza kadar asılı bırakırdı.
 */
public interface OrderMessagePublisher {

    void orderPlaced(OrderPlaced event, Instant at);

    void orderConfirmed(String orderId, Instant at);

    void orderCancelled(String orderId, String reason, Instant at);

    void reserveStock(String orderId, List<OrderLine> lines, Instant at);

    /** Telafi: ödeme başarısız olduğunda tutulan stoğu geri bırakır. */
    void releaseStock(String orderId, String reservationId, Instant at);

    void processPayment(String orderId, String customerId, Money amount, Instant at);

    /** Telafi: ödeme sonrası bir adım başarısız olursa tahsilatı iade eder. */
    void refundPayment(String orderId, String paymentId, Money amount, Instant at);
}
