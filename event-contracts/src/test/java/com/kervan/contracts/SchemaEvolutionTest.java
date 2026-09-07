package com.kervan.contracts;

import com.kervan.contracts.order.v1.OrderItem;
import com.kervan.contracts.order.v1.OrderPlaced;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaIncompatibilityType;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.apache.avro.SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE;
import static org.apache.avro.SchemaCompatibility.SchemaCompatibilityType.INCOMPATIBLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Şema değişikliklerinin hangisinin güvenli, hangisinin yıkıcı olduğunu gösterir.
 *
 * <p><b>Sorun.</b> Bir olayı yayınlayan servis ile onu tüketen servis ayrı ayrı
 * dağıtılır. İkisi asla aynı anda güncellenmez. Yani şema değiştiğinde bir süre
 * eski üretici ile yeni tüketici (ya da tersi) yan yana çalışır. Şema değişikliği
 * bunu kaldıramıyorsa, dağıtım anında olaylar okunamaz hâle gelir.
 *
 * <p><b>İki yön.</b>
 * <ul>
 *   <li><b>Geriye uyumluluk (backward):</b> YENİ okuyucu, ESKİ veriyi okuyabilir.
 *       Önce tüketiciyi güncellemek istiyorsan bu gerekir.</li>
 *   <li><b>İleriye uyumluluk (forward):</b> ESKİ okuyucu, YENİ veriyi okuyabilir.
 *       Önce üreticiyi güncellemek istiyorsan bu gerekir.</li>
 * </ul>
 *
 * <p>Confluent Schema Registry aynı kontrolü kayıt anında yapar ve uyumsuz şemayı
 * reddeder. Bu testler o kuralı derleme/test zamanına çeker: uyumsuz bir değişiklik
 * çalışan sisteme değil, önce CI'ya çarpar.
 */
class SchemaEvolutionTest {

    private static final Schema V1 = OrderPlaced.getClassSchema();

    @Test
    @DisplayName("varsayılanı olan isteğe bağlı alan eklemek her iki yönde de güvenlidir")
    void addingOptionalFieldWithDefaultIsSafeBothWays() {
        Schema v2 = load("evolution/v2-optional-coupon.avsc");

        assertThat(compatibility(v2, V1)).isEqualTo(COMPATIBLE);   // yeni okuyucu, eski veri
        assertThat(compatibility(V1, v2)).isEqualTo(COMPATIBLE);   // eski okuyucu, yeni veri
    }

    @Test
    @DisplayName("eski veri yeni şemayla okunduğunda eklenen alan varsayılanla dolar")
    void oldDataReadWithNewSchemaFallsBackToDefault() throws IOException {
        Schema v2 = load("evolution/v2-optional-coupon.avsc");

        // Olay v1 ile yazıldı: baytların içinde kupon alanı hiç yok.
        GenericRecord read = readWith(v2, encodeV1());

        assertThat(read.get("couponCode")).isNull();
        assertThat(read.get("currency")).hasToString("TRY");
        assertThat(read.get("orderId")).hasToString("siparis-1");
    }

    @Test
    @DisplayName("varsayılanı olmayan alan eklemek geriye uyumluluğu bozar")
    void addingFieldWithoutDefaultBreaksBackwardCompatibility() {
        Schema v2 = load("evolution/v2-required-coupon.avsc");

        SchemaCompatibility.SchemaPairCompatibility result =
                SchemaCompatibility.checkReaderWriterCompatibility(v2, V1);

        assertThat(result.getType()).isEqualTo(INCOMPATIBLE);
        // Sebebi de sabitliyoruz: alan var ama eski veride karşılığı yok ve
        // konulacak bir varsayılan da tanımlanmamış.
        assertThat(result.getResult().getIncompatibilities())
                .extracting(SchemaCompatibility.Incompatibility::getType)
                .contains(SchemaIncompatibilityType.READER_FIELD_MISSING_DEFAULT_VALUE);
    }

    @Test
    @DisplayName("alan silmek ileriye uyumluluğu bozar")
    void removingFieldBreaksForwardCompatibility() {
        Schema v2 = load("evolution/v2-currency-removed.avsc");

        // Yeni şemayla yazılan veride currency yok; hâlâ v1 okuyan tüketici
        // bu alanı dolduramaz.
        assertThat(compatibility(V1, v2)).isEqualTo(INCOMPATIBLE);

        // Ters yön sorunsuz: yeni okuyucu eski veriyi okur, fazla alanı yok sayar.
        assertThat(compatibility(v2, V1)).isEqualTo(COMPATIBLE);
    }

    private static SchemaCompatibility.SchemaCompatibilityType compatibility(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType();
    }

    private static Schema load(String resource) {
        try (InputStream in = SchemaEvolutionTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Şema bulunamadı: " + resource);
            }
            // Her dosya için yeni parser: aynı isimli kayıt tipleri çakışmasın.
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOExceptionWrapper(resource, e);
        }
    }

    private static byte[] encodeV1() throws IOException {
        OrderPlaced event = OrderPlaced.newBuilder()
                .setOrderId("siparis-1")
                .setCustomerId("musteri-1")
                .setTotalAmount(new BigDecimal("100.0000"))
                .setCurrency("TRY")
                .setItems(List.of(OrderItem.newBuilder()
                        .setProductId("urun-1")
                        .setSku("SKU-1")
                        .setQuantity(1)
                        .setUnitPrice(new BigDecimal("100.0000"))
                        .build()))
                .setPlacedAt(Instant.parse("2026-03-01T10:15:30Z"))
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        new SpecificDatumWriter<>(OrderPlaced.class).write(event, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    /** Yazan şema v1, okuyan şema parametredeki şema — Avro ikisini eşleştirir. */
    private static GenericRecord readWith(Schema readerSchema, byte[] bytes) throws IOException {
        return new GenericDatumReader<GenericRecord>(V1, readerSchema)
                .read(null, DecoderFactory.get().binaryDecoder(bytes, null));
    }

    private static final class UncheckedIOExceptionWrapper extends RuntimeException {
        UncheckedIOExceptionWrapper(String resource, IOException cause) {
            super("Şema okunamadı: " + resource, cause);
        }
    }
}
