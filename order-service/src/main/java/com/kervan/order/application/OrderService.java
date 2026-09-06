package com.kervan.order.application;

import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.application.exception.OrderAccessDeniedException;
import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.event.OrderPlaced;
import com.kervan.order.domain.model.Caller;
import com.kervan.order.domain.model.Money;
import com.kervan.order.domain.model.Order;
import com.kervan.order.domain.model.OrderLine;
import com.kervan.order.domain.model.OutboxMessage;
import com.kervan.order.domain.port.OrderEventSerializer;
import com.kervan.order.domain.port.OrderRepository;
import com.kervan.order.domain.port.OutboxRepository;
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

    private static final String AGGREGATE_TYPE = "Order";

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final OrderEventSerializer eventSerializer;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository,
                        OutboxRepository outboxRepository,
                        OrderEventSerializer eventSerializer,
                        Clock clock) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.eventSerializer = eventSerializer;
        this.clock = clock;
    }

    /**
     * Siparişi ve ona ait {@code OrderPlaced} olayını tek transaction'da yazar.
     * <p>
     * Olay burada Kafka'ya gönderilmez; yalnızca outbox tablosuna yazılır. Taşıma işi
     * {@code OutboxPublisher}'a aittir. Gerekçe: {@link OutboxMessage}.
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
        outboxRepository.save(toOutboxMessage(saved, now));

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

    private OutboxMessage toOutboxMessage(Order order, Instant now) {
        OrderPlaced event = new OrderPlaced(
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

        // Serileştirme transaction'ın İÇİNDE yapılır. Olay serileştirilemiyorsa
        // (örneğin şema Registry tarafından reddedildiyse) sipariş de yazılmaz:
        // kimsenin duymayacağı bir sipariş oluşturmaktansa isteği reddetmek doğrudur.
        return OutboxMessage.pending(
                AGGREGATE_TYPE, order.id(), OrderPlaced.EVENT_TYPE,
                eventSerializer.serialize(event), now);
    }
}
