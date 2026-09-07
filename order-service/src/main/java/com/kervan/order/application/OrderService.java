package com.kervan.order.application;

import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.application.exception.OrderAccessDeniedException;
import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Caller;
import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderLine;
import com.kervan.order.domain.model.OrderSaga;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OrderMessagePublisher;
import com.kervan.order.domain.port.OrderRepository;
import com.kervan.order.domain.port.SagaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

/**
 * Sipariş use-case'leri.
 * <p>
 * {@link #placeOrder} içindeki iki yazma işlemi (sipariş + outbox kaydı) aynı
 * transaction'dadır. Bu, servisin en önemli tek satırlık kuralıdır: olay ile sipariş
 * ya birlikte var olur ya hiç olmaz.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaRepository sagaRepository;
    private final OrderMessagePublisher messages;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository,
                        SagaRepository sagaRepository,
                        OrderMessagePublisher messages,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Siparişi, olayını ve saga'nın ilk komutunu tek transaction'da yazar.
     * <p>
     * Hiçbiri Kafka'ya doğrudan gönderilmez; outbox tablosuna yazılır ve oradan
     * taşınır. Gerekçe: {@link OutboxMessage}.
     * <p>
     * Serileştirme de bu transaction'ın içinde yapılır: şema Registry tarafından
     * reddedilirse sipariş de yazılmaz. Kimsenin duymayacağı bir sipariş
     * oluşturmaktansa isteği reddetmek doğrudur.
     */
    @Transactional
    public Order placeOrder(PlaceOrderCommand command, Caller caller) {
        Instant now = clock.instant();
        Currency currency = Currency.getInstance(command.currency());

        List<OrderLine> lines = command.lines().stream()
                .map(line -> new OrderLine(
                        line.productId(),
                        line.sku(),
                        line.quantity(),
                        new Money(line.unitPrice(), currency)))
                .toList();

        // Sipariş sahibi token'dan alınır, istek gövdesinden değil. Aksi hâlde bir
        // müşteri başka bir müşterinin adına sipariş oluşturabilirdi.
        Order saved = orderRepository.save(Order.place(caller.userId(), lines, now));

        // Olay ve saga'nın ilk komutu siparişle AYNI transaction'da yazılır.
        // Komut ayrı bir adımda gönderilseydi, araya giren bir çökme siparişi
        // oluşturur ama saga'yı hiç başlatmazdı: müşteri sipariş verdiğini görür,
        // arkada hiçbir şey olmaz.
        messages.orderPlaced(toEvent(saved, now), now);
        sagaRepository.save(OrderSaga.started(saved.id(), now));
        messages.reserveStock(saved.id(), saved.lines(), now);

        return saved;
    }

    /**
     * Siparişi döner. Yönetici her siparişi görebilir; müşteri yalnızca kendisininkini.
     *
     * @throws OrderNotFoundException     sipariş yoksa
     * @throws OrderAccessDeniedException sipariş var ama çağıranın değilse
     */
    @Transactional(readOnly = true)
    public Order getOrder(String orderId, Caller caller) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!caller.isAdmin() && !caller.owns(order)) {
            throw new OrderAccessDeniedException(orderId);
        }
        return order;
    }

    private OrderPlaced toEvent(Order order, Instant now) {
        return new OrderPlaced(
                order.id(),
                order.customerId(),
                order.totalAmount().amount(),
                order.totalAmount().currency().getCurrencyCode(),
                order.lines().stream()
                        .map(line -> new OrderPlaced.Item(
                                line.productId(),
                                line.sku(),
                                line.quantity(),
                                line.unitPrice().amount()))
                        .toList(),
                order.placedAt());
    }
}
