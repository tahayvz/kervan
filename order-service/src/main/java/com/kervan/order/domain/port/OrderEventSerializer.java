package com.kervan.order.domain.port;

import com.kervan.order.domain.event.OrderPlaced;

/**
 * Olayı, outbox tablosuna yazılacak baytlara çevirir.
 *
 * <p><b>Neden bir port?</b> Uygulama katmanı olayın hangi biçimde (Avro, JSON,
 * Protobuf) taşındığını bilmemelidir. Biçim, servisin dış dünyayla anlaştığı bir
 * altyapı ayrıntısıdır ve değişebilir. Uygulama katmanı yalnızca "bu olayı
 * baytlara çevir" der; nasıl çevrildiğini {@code infrastructure} altındaki
 * uygulaması bilir.
 *
 * <p>Bugünkü uygulama Avro kullanır ve şemayı Schema Registry'ye kaydeder
 * (ADR-0008).
 */
public interface OrderEventSerializer {

    /**
     * @return Confluent kablo biçiminde baytlar: 1 sihirli bayt + 4 baytlık şema
     * kimliği + Avro ikili gövde. Şema kimliği baytların içinde taşındığı için
     * okuyan taraf şemayı Registry'den kendisi bulur.
     */
    byte[] serialize(OrderPlaced event);
}
