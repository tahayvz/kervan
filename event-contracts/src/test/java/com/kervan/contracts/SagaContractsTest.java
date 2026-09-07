package com.kervan.contracts;

import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bütün sözleşmelerde ortak olması gereken kuralları sabitler.
 *
 * <p>Bunlar tek tek şemalara bakarak görülemeyen kurallardır: yeni bir şema eklendiğinde
 * ihlal edildiklerinde kimse fark etmez, çünkü o şema kendi başına geçerlidir. Test,
 * kuralı şema sayısından bağımsız hâle getirir — klasördeki her dosya denetlenir.
 */
@DisplayName("Sözleşme kuralları")
class SagaContractsTest {

    private static final Path SCHEMA_DIR = Path.of("src/main/avro");

    private static List<Schema> allSchemas() {
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            return files
                    .filter(path -> path.toString().endsWith(".avsc"))
                    .sorted()
                    .map(SagaContractsTest::parse)
                    // Her dosya için yeni parser: aynı isimli iç tipler çakışmasın.
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Schema parse(Path path) {
        try {
            return new Schema.Parser().parse(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("klasördeki her şema okunabiliyor")
    void everySchemaParses() {
        // Ayrıca testin gerçekten bir şey taradığını garanti eder: klasör boşalsa
        // ya da yol değişse aşağıdaki kurallar sessizce hiçbir şeyi denetlemezdi.
        assertThat(allSchemas()).hasSizeGreaterThanOrEqualTo(13);
    }

    @Test
    @DisplayName("her mesaj orderId taşır — saga anahtarı odur")
    void everyMessageCarriesTheSagaKey() {
        // Bütün saga mesajları sipariş kimliğiyle anahtarlanır; aynı siparişin
        // adımları aynı partition'a düşsün ve sırası korunsun diye (ADR-0009).
        // Bir mesaj bu alanı taşımazsa anahtarlanamaz ve sıra garantisinin dışına
        // düşer — üstelik bu, çalışma anında hata vermez.
        assertThat(allSchemas()).allSatisfy(schema -> {
            Schema.Field orderId = schema.getField("orderId");
            assertThat(orderId)
                    .withFailMessage("%s içinde orderId yok", schema.getFullName())
                    .isNotNull();
            assertThat(orderId.schema().getType()).isEqualTo(Schema.Type.STRING);
        });
    }

    @Test
    @DisplayName("her para alanı aynı ölçekte")
    void everyMonetaryFieldUsesTheSameScale() {
        // Farklı ölçekler, aynı tutarın iki mesajda farklı görünmesi demektir; ayrıca
        // aralarında dönüşüm yapan kod er geç yuvarlar. Ölçek veritabanındaki
        // NUMERIC(19,4) ile aynı tutuldu.
        assertThat(allSchemas()).allSatisfy(schema ->
                decimalsIn(schema).forEach(decimal -> {
                    assertThat(decimal.getScale()).isEqualTo(4);
                    assertThat(decimal.getPrecision()).isEqualTo(19);
                }));
    }

    @Test
    @DisplayName("her zaman damgası milisaniye çözünürlüğünde")
    void everyTimestampUsesTheSameResolution() {
        assertThat(allSchemas()).allSatisfy(schema ->
                schema.getFields().stream()
                        .map(field -> field.schema().getLogicalType())
                        .filter(type -> type instanceof LogicalTypes.TimestampMillis
                                || type instanceof LogicalTypes.TimestampMicros)
                        .forEach(type -> assertThat(type)
                                .isInstanceOf(LogicalTypes.TimestampMillis.class)));
    }

    /** Kayıttaki ve dizi içindeki iç kayıtlardaki bütün decimal alanlar. */
    private static Stream<LogicalTypes.Decimal> decimalsIn(Schema schema) {
        return schema.getFields().stream()
                .flatMap(field -> switch (field.schema().getType()) {
                    case ARRAY -> field.schema().getElementType().getType() == Schema.Type.RECORD
                            ? field.schema().getElementType().getFields().stream()
                                    .map(inner -> inner.schema().getLogicalType())
                            : Stream.<LogicalType>empty();
                    default -> Stream.of(field.schema().getLogicalType());
                })
                .filter(LogicalTypes.Decimal.class::isInstance)
                .map(LogicalTypes.Decimal.class::cast);
    }
}
