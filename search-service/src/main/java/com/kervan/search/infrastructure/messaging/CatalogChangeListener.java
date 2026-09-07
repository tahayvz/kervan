package com.kervan.search.infrastructure.messaging;

import com.kervan.search.application.ProductProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Katalog değişiklik akışını dinler.
 *
 * <p>Mesajlar Avro değil düz JSON: bu akış Debezium'un ürettiği <b>ham değişiklik
 * kaydıdır</b>, sürümlü bir domain olayı değil (ADR-0004). Sözleşmesi olmadığını
 * bilerek tüketiyoruz — bedeli, katalog belgesinin alan adları değişirse buranın
 * kırılmasıdır.
 *
 * <p>Silme olaylarında gövde boş gelir; o yüzden anahtar da alınır.
 */
@Component
class CatalogChangeListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogChangeListener.class);

    private final DebeziumChangeEventMapper mapper;
    private final ProductProjection projection;

    CatalogChangeListener(DebeziumChangeEventMapper mapper, ProductProjection projection) {
        this.mapper = mapper;
        this.projection = projection;
    }

    @KafkaListener(topics = "${kervan.search.catalog-topic}")
    void onCatalogChange(@Payload(required = false) String value,
                         @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (value == null) {
            // Tombstone: Debezium silme sonrası null gövdeli bir kayıt yazabilir.
            // Silme zaten "d" olayıyla geldi; bunu yok saymak doğru.
            log.debug("Boş gövdeli kayıt yok sayıldı: key={}", key);
            return;
        }
        mapper.toChange(key, value).ifPresent(projection::apply);
    }
}
