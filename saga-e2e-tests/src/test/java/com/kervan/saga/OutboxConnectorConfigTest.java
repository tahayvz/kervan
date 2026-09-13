package com.kervan.saga;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Debezium konektör dosyalarını sistemin geri kalanına <b>bağlar</b>.
 *
 * <h2>Hangi boşluğu kapatıyor</h2>
 * ADR-0020'de açıkça yazılmıştı: saga testi Debezium'u çalıştırmıyor, dolayısıyla
 * <em>"konektör JSON'undaki bir hata CI'yı kırmaz"</em>. Bu test o cümleyi kısmen
 * yanlışlar.
 *
 * <p>Yaptığı şey Debezium'u çalıştırmak değil — bunun yerine <b>iki checked-in
 * dosyanın birbiriyle konuştuğunu</b> sınıyor: konektörün yazdığı konu ile servisin
 * dinlediği konu, konektörün okuduğu sütun ile göçün yarattığı sütun. Bu ikisi
 * ayrıldığında sistem sessizce çalışmaz hâle gelir: hiçbir hata çıkmaz, mesaj
 * yalnızca kimsenin dinlemediği bir konuya gider.
 *
 * <h2>Neden {@code src/main/resources}, {@code target/classes} değil</h2>
 * {@link SagaEnvironment} uygulamayı <em>çalıştırdığı</em> için onun okuduğu dosyayı
 * kullanmak zorunda. Buradaki soru farklı: "depodaki dosyalar birbiriyle tutarlı mı".
 * Sorunun öznesi depo olduğu için kaynak dosyalar okunuyor; böylece eski bir derleme
 * çıktısı testi yanıltamaz (günlük B42).
 *
 * <h2>Neyi hâlâ doğrulamıyor</h2>
 * Debezium'un çalıştığını. Mantıksal çözümleme, yayın (publication), replication slot,
 * snapshot davranışı — hiçbiri burada sınanmaz. Bu test yalnızca <em>değerlerin</em>
 * doğru olduğunu söyler, <em>düzeneğin çalıştığını</em> değil.
 */
@DisplayName("Debezium outbox konektör ayarları")
class OutboxConnectorConfigTest {

    private static final String ORDER = "order-outbox-connector.json";
    private static final String INVENTORY = "inventory-outbox-connector.json";
    private static final String PAYMENT = "payment-outbox-connector.json";

    private static Stream<ConnectorConfig> allConnectors() {
        return Stream.of(ORDER, INVENTORY, PAYMENT).map(ConnectorConfig::load);
    }

    /** Bir servisin {@code application.yml}'ini düzleştirilmiş anahtarlarla okur. */
    private static Properties configOf(String module) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                Path.of("..", module, "src", "main", "resources", "application.yml")
                        .toAbsolutePath().normalize().toFile()));
        return yaml.getObject();
    }

    private static String migrationsOf(String module) {
        Path dir = Path.of("..", module, "src", "main", "resources", "db", "migration")
                .toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(dir)) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                all.append(Files.readString(file)).append('\n');
            }
            return all.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Göçler okunamadı: " + dir, e);
        }
    }

    @Nested
    @DisplayName("Her outbox konektöründe sabit olması gerekenler")
    class Invariants {

        @Test
        @DisplayName("payload BAYT olarak taşınır")
        void payloadStaysBytes() {
            // Uygulama payload'ı zaten Avro'ya serileştirip Confluent kablo biçiminde
            // (sihirli bayt + şema kimliği) tabloya yazıyor. Başka bir dönüştürücü onu
            // BİR KEZ DAHA sarar ve tüketici tarafta çözülemez hâle getirir.
            allConnectors().forEach(c -> assertThat(c.get("value.converter"))
                    .as("value.converter (%s)", c.name())
                    .isEqualTo("org.apache.kafka.connect.converters.ByteArrayConverter"));
        }

        @Test
        @DisplayName("başlık dönüştürücüsü StringConverter — varsayılan JSON İZ BAĞLAMINI BOZAR")
        void headerConverterIsString() {
            // Varsayılan JSON'dur ve değeri TIRNAK İÇİNDE yazar: "00-abc...-01".
            // W3C traceparent ayrıştırıcısı tırnaklı değeri geçersiz sayar ve SESSİZCE
            // atar; izleme zinciri Jaeger'da kopuk görünür, hiçbir yerde hata çıkmaz.
            allConnectors().forEach(c -> assertThat(c.get("header.converter"))
                    .as("header.converter (%s)", c.name())
                    .isEqualTo("org.apache.kafka.connect.storage.StringConverter"));
        }

        @Test
        @DisplayName("KALP ATIŞI YOK — açık olsaydı görev sessizce ölürdü")
        void noHeartbeat() {
            // Kalp atışı mesajı bir STRUCT'tır; ByteArrayConverter bayt bekler ve görev
            // ölür. En kötüsü: bu YALNIZCA AKIŞ SESSİZKEN olur, yani yük altında
            // görünmez, gece görünür.
            allConnectors().forEach(c -> assertThat(c.values().keySet())
                    .as("kalp atışı ayarı (%s)", c.name())
                    .noneMatch(key -> key.contains("heartbeat")));
        }

        @Test
        @DisplayName("snapshot.mode = no_data")
        void snapshotTakesNoData() {
            // Konektör VERİDEN ÖNCE kaydedilmeli. Snapshot açık olsaydı, var olan
            // outbox satırları yeniden yayınlanır ve tüketiciler onları ikinci kez
            // işlerdi.
            allConnectors().forEach(c -> assertThat(c.get("snapshot.mode"))
                    .as("snapshot.mode (%s)", c.name()).isEqualTo("no_data"));
        }

        @Test
        @DisplayName("yalnızca outbox tablosu izlenir")
        void watchesOnlyTheOutboxTable() {
            allConnectors().forEach(c -> assertThat(c.get("table.include.list"))
                    .as("table.include.list (%s)", c.name()).isEqualTo("public.outbox_messages"));
        }

        @Test
        @DisplayName("EventRouter kullanılır ve iz bağlamı başlığa taşınır")
        void usesEventRouterAndCarriesTraceParent() {
            allConnectors().forEach(c -> {
                assertThat(c.get("transforms.outbox.type"))
                        .as("transforms.outbox.type (%s)", c.name())
                        .isEqualTo("io.debezium.transforms.outbox.EventRouter");
                // ADR-0013: iz bağlamı outbox satırında taşınır ve buradan Kafka
                // başlığına kopyalanır. Bu satır giderse izleme zinciri kopar.
                assertThat(c.traceParentHeader())
                        .as("traceparent başlığı (%s)", c.name()).isEqualTo("traceparent");
            });
        }
    }

    @Nested
    @DisplayName("Konektör, göçlerin yarattığı sütunları okur")
    class ColumnsExist {

        private void assertColumnsExist(String connectorFile, String module) {
            ConnectorConfig c = ConnectorConfig.load(connectorFile);
            String migrations = migrationsOf(module);

            for (String column : List.of(c.idColumn(), c.keyColumn(),
                    c.payloadColumn(), c.traceParentColumn())) {
                // Sütun adı göçlerde hiç geçmiyorsa konektör olmayan bir şeyi okuyor
                // demektir. Debezium bunu ancak ÇALIŞMA ANINDA fark eder.
                assertThat(migrations)
                        .as("%s: '%s' sütunu %s göçlerinde yok", connectorFile, column, module)
                        .contains(column);
            }
        }

        @Test
        @DisplayName("order")
        void order() {
            assertColumnsExist(ORDER, "order-service");
        }

        @Test
        @DisplayName("inventory")
        void inventory() {
            assertColumnsExist(INVENTORY, "inventory-service");
        }

        @Test
        @DisplayName("payment")
        void payment() {
            assertColumnsExist(PAYMENT, "payment-service");
        }
    }

    @Nested
    @DisplayName("Konektörün yazdığı konu, servisin dinlediği konudur")
    class TopicsAgree {

        /**
         * <b>Bu testin en değerli iddiası.</b> İki dosya ayrıldığında sistem sessizce
         * çalışmaz hâle gelir: mesaj üretilir, kimsenin dinlemediği bir konuya gider,
         * saga cevabını sonsuza kadar bekler ve hiçbir yerde hata çıkmaz.
         */
        @Test
        @DisplayName("inventory olayları: konektör -> order-service'in dinlediği konu")
        void inventoryEvents() {
            String published = ConnectorConfig.load(INVENTORY).fixedTopic();

            assertThat(published)
                    .as("inventory-service'in kendi ayarı")
                    .isEqualTo(configOf("inventory-service").getProperty("kervan.inventory.events-topic"));
            assertThat(published)
                    .as("order-service'in dinlediği konu")
                    .isEqualTo(configOf("order-service").getProperty("kervan.topics.inventory-events"));
        }

        @Test
        @DisplayName("payment olayları: konektör -> order-service'in dinlediği konu")
        void paymentEvents() {
            String published = ConnectorConfig.load(PAYMENT).fixedTopic();

            assertThat(published)
                    .as("payment-service'in kendi ayarı")
                    .isEqualTo(configOf("payment-service").getProperty("kervan.payment.events-topic"));
            assertThat(published)
                    .as("order-service'in dinlediği konu")
                    .isEqualTo(configOf("order-service").getProperty("kervan.topics.payment-events"));
        }

        @Test
        @DisplayName("order konektörü hedefi SATIRDAN alır, sabit konuya yazmaz")
        void orderRoutesPerRow() {
            ConnectorConfig order = ConnectorConfig.load(ORDER);

            // order-service tek bir konuya değil ÜÇ yere yazar: kendi olayları,
            // inventory komutları, payment komutları. Hedef bu yüzden satırda duruyor.
            // Sabit bir konuya çevrilseydi komutlar yanlış yere düşerdi.
            assertThat(order.get("transforms.outbox.route.by.field")).isEqualTo("destination");
            assertThat(order.fixedTopic()).isEqualTo("${routedByValue}");
        }

        @Test
        @DisplayName("konektör doğru veritabanına bakar")
        void databasesMatch() {
            assertThat(ConnectorConfig.load(ORDER).get("database.dbname")).isEqualTo("orders");
            assertThat(ConnectorConfig.load(INVENTORY).get("database.dbname")).isEqualTo("inventory");
            assertThat(ConnectorConfig.load(PAYMENT).get("database.dbname")).isEqualTo("payment");
        }
    }

    @Test
    @DisplayName("eksik bir ayar sessizce null dönmez, açıkça hata verir")
    void missingKeyFailsLoudly() {
        // ConnectorConfig'in kendi güvenliği: bir anahtar silinirse test "beklenen X,
        // gelen null" gibi anlaşılmaz bir yerde değil, adını söyleyerek kırılsın.
        assertThatThrownBy(() -> ConnectorConfig.load(INVENTORY).get("boyle.bir.ayar.yok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boyle.bir.ayar.yok");
    }
}
