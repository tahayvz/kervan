package com.kervan.contracts;

import com.kervan.contracts.order.v1.OrderItem;
import com.kervan.contracts.order.v1.OrderPlaced;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Olayın Avro ikili biçimine yazılıp aynen geri okunabildiğini doğrular.
 *
 * <p>Buradaki asıl mesele para. Şema {@code totalAmount} alanını
 * {@code decimal} olarak tanımlar, {@code double} olarak değil. Bu testler
 * kuruşun kaybolmadığını gösterir.
 */
class OrderPlacedSerializationTest {

    @Test
    @DisplayName("olay ikili biçime yazılıp aynı değerlerle geri okunur")
    void roundTripsThroughBinaryEncoding() throws IOException {
        OrderPlaced original = sampleOrder();

        OrderPlaced decoded = decode(encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("tutar kuruşuna kadar korunur")
    void keepsMonetaryAmountExact() throws IOException {
        OrderPlaced original = OrderPlaced.newBuilder(sampleOrder())
                .setTotalAmount(new BigDecimal("1299.99"))
                .build();

        OrderPlaced decoded = decode(encode(original));

        // compareTo değil isEqualTo: ölçek (scale) de aynı kalmalı.
        // 1299.99 ile 1299.990 aynı sayıdır ama aynı para gösterimi değildir.
        assertThat(decoded.getTotalAmount()).isEqualTo(new BigDecimal("1299.99"));
    }

    @Test
    @DisplayName("zaman damgası milisaniye çözünürlüğünde taşınır")
    void carriesTimestampAtMillisecondResolution() throws IOException {
        Instant withNanos = Instant.parse("2026-03-01T10:15:30.123456789Z");
        OrderPlaced original = OrderPlaced.newBuilder(sampleOrder())
                .setPlacedAt(withNanos)
                .build();

        OrderPlaced decoded = decode(encode(original));

        // Şema timestamp-millis; nanosaniye alanı kırpılır. Test bunu belgeler:
        // olayı üreten tarafın nanosaniye hassasiyetine güvenmemesi gerekir.
        assertThat(decoded.getPlacedAt()).isEqualTo(Instant.parse("2026-03-01T10:15:30.123Z"));
    }

    @Test
    @DisplayName("şema alan adlarını taşımaz, bu yüzden çıktı JSON'dan küçüktür")
    void producesCompactPayload() throws IOException {
        byte[] avro = encode(sampleOrder());

        // Avro alan adlarını yazmaz; okuyan taraf şemadan bilir. Aynı veri JSON'da
        // her satırda "productId", "unitPrice" gibi anahtarları tekrar taşırdı.
        assertThat(avro.length).isLessThan(200);
    }

    private static OrderPlaced sampleOrder() {
        return OrderPlaced.newBuilder()
                .setOrderId("11111111-1111-1111-1111-111111111111")
                .setCustomerId("musteri-1")
                .setTotalAmount(new BigDecimal("259.90"))
                .setCurrency("TRY")
                .setItems(List.of(OrderItem.newBuilder()
                        .setProductId("urun-1")
                        .setSku("SKU-1")
                        .setQuantity(2)
                        .setUnitPrice(new BigDecimal("129.95"))
                        .build()))
                .setPlacedAt(Instant.parse("2026-03-01T10:15:30Z"))
                .build();
    }

    private static byte[] encode(OrderPlaced event) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<>(OrderPlaced.class).write(event, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private static OrderPlaced decode(byte[] bytes) throws IOException {
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
        return new SpecificDatumReader<>(OrderPlaced.class).read(null, decoder);
    }
}
