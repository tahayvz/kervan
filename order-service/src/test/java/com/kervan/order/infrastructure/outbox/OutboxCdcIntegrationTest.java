package com.kervan.order.infrastructure.outbox;

import com.kervan.contracts.order.v1.OrderItem;
import com.kervan.contracts.order.v1.OrderPlaced;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Debezium'un outbox tablosunu okuyup olayı Kafka'ya taşıdığını doğrular.
 *
 * <p><b>Neden bu test var?</b> Konektör ayarları bir JSON dosyasıdır; derleyici onu
 * denetlemez. Bir alan adı yanlış yazılsa ya da dönüştürücü (converter) yanlış
 * seçilse hiçbir şey kırılmaz — olaylar sessizce akmaz ya da okunamaz biçimde akar.
 * Bu test, depodaki <b>gerçek</b> ayar dosyasını yükler ve yalnızca veritabanına
 * satır yazarak olayın Kafka'ya ulaşmasını bekler.
 *
 * <p>Uygulama hiç çalışmaz. Zaten mesele budur: Debezium devredeyken uygulamanın
 * Kafka'ya dokunması gerekmez.
 *
 * <p>Payload, taşıma sırasında <b>değiştirilmemelidir</b>. Test bunu, mesajı
 * üretilmiş Avro sınıfıyla çözerek doğrular: baytlar bozulsaydı çözme başarısız
 * olurdu.
 */
@DisplayName("Debezium CDC: outbox -> Kafka")
class OutboxCdcIntegrationTest {

    private static final String TOPIC = "kervan.orders.events";
    private static final String REGISTRY_URL = "mock://outbox-cdc-it";
    /** Konektör ayarındaki {@code slot.name} ile aynı olmalı. */
    private static final String SLOT_NAME = "kervan_order_outbox";

    private static final Path CONNECTOR_CONFIG =
            Path.of("../infra/docker/debezium/order-outbox-connector.json");

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withDatabaseName("orders")
                    // Debezium tabloyu sorgulamaz, WAL'ı okur. Varsayılan
                    // wal_level=replica satır içeriğini WAL'a yazmaz.
                    .withCommand("postgres", "-c", "wal_level=logical");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    /**
     * Debezium 3.0.0.Final imajının <b>linux/amd64</b> sürümü, sindirim (digest) ile
     * sabitlenmiş hâlde.
     *
     * <p>Neden etiket değil de digest? Etiket ({@code :3.0.0.Final}) çalıştığın
     * makinenin mimarisine göre farklı bir imaja çözülür. ARM (Apple Silicon)
     * sürümündeki Java çalışma zamanı bazı makinelerde daha ilk yerel çağrıda çöküyor
     * ({@code SIGILL ... System.registerNatives ... linux-aarch64}); bu, buradaki
     * koddan bağımsızdır, imaj yalnızca {@code java -version} çalıştırırken bile
     * aynı şekilde çöker. Digest, her yerde aynı amd64 imajını seçer: CI'da
     * (linux/amd64) yerel, Apple Silicon'da emülasyonla çalışır.
     *
     * <p>Sürüm yükseltilirken bu digest de güncellenmelidir:
     * {@code docker manifest inspect quay.io/debezium/connect:<sürüm>}
     */
    private static final String CONNECT_IMAGE = "quay.io/debezium/connect"
            + "@sha256:6d1458c3a319ef9a30041ae7a78f567aad65d19bc256cd3c19691444cd0e647a";

    private static final GenericContainer<?> CONNECT =
            new GenericContainer<>(DockerImageName.parse(CONNECT_IMAGE))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("connect")
                    .withExposedPorts(8083)
                    .withEnv("BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("GROUP_ID", "kervan-connect-test")
                    .withEnv("CONFIG_STORAGE_TOPIC", "_connect_configs")
                    .withEnv("OFFSET_STORAGE_TOPIC", "_connect_offsets")
                    .withEnv("STATUS_STORAGE_TOPIC", "_connect_status")
                    .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                    .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
                    .waitingFor(Wait.forHttp("/connectors").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(3));

    @BeforeAll
    static void startInfrastructure() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        startConnectOrSkipLocally();

        // Şemayı gerçek migration'lar kurar; V3'ün (payload TEXT -> BYTEA)
        // mantıksal WAL açıkken de çalıştığı böylece doğrulanmış olur.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        registerConnector();
        awaitReplicationSlotStreaming();
    }

    @Test
    @DisplayName("outbox satırı yazılır yazılmaz olay konuya düşer")
    void rowWrittenToOutboxReachesKafka() throws Exception {
        String orderId = UUID.randomUUID().toString();
        byte[] payload = avroPayload(orderId);

        insertOutboxRow(orderId, payload);

        try (KafkaConsumer<String, OrderPlaced> consumer = avroConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            ConsumerRecord<String, OrderPlaced> record = await()
                    .atMost(Duration.ofSeconds(60))
                    .until(() -> pollFor(consumer, orderId), r -> r != null);

            // Anahtar aggregate_id'dir: aynı siparişin olayları aynı partition'a düşer.
            assertThat(record.key()).isEqualTo(orderId);

            // Baytlar taşıma sırasında hiç dokunulmadan geldi; aksi hâlde
            // Avro çözümlemesi patlardı.
            assertThat(record.value().getOrderId()).isEqualTo(orderId);
            assertThat(record.value().getCurrency()).isEqualTo("TRY");
            assertThat(record.value().getTotalAmount()).isEqualByComparingTo("249.90");
        }
    }

    @Test
    @DisplayName("olay kimliği başlıkta taşınır — tüketici tekrarları bundan eler")
    void carriesEventIdHeaderForIdempotency() throws Exception {
        String orderId = UUID.randomUUID().toString();

        insertOutboxRow(orderId, avroPayload(orderId));

        try (KafkaConsumer<String, byte[]> consumer = rawConsumer()) {
            consumer.subscribe(List.of(TOPIC));

            ConsumerRecord<String, byte[]> record = await()
                    .atMost(Duration.ofSeconds(60))
                    .until(() -> pollRawFor(consumer, orderId), r -> r != null);

            // Teslimat en az bir kezdir; aynı olay tekrar gelebilir. EventRouter
            // outbox satırının kimliğini başlığa koyar, tüketici tekrarı buradan
            // tanır. Anahtar (sipariş kimliği) bu iş için yeterli değildir:
            // aynı siparişin birden çok olayı olur.
            assertThat(record.headers().lastHeader("id")).isNotNull();
        }
    }

    /**
     * Kafka Connect'i başlatır; başlatamazsa yalnızca <b>yerel makinede</b> testi atlar.
     *
     * <p>Sebep: Debezium'un ARM (Apple Silicon) imajındaki Java çalışma zamanı bazı
     * makinelerde daha ilk yerel çağrıda çöküyor:
     * {@code SIGILL ... java.lang.System.registerNatives ... linux-aarch64}. Bu, bu
     * depodaki koddan ya da konektör ayarından bağımsızdır; imajın kendisi
     * {@code java -version} çalıştırırken bile aynı şekilde çöker.
     *
     * <p>CI ortamında (linux/amd64) <b>atlama yoktur</b>: orada başlatma hatası
     * doğrudan teste yansır. Aksi hâlde bu test hiçbir yerde çalışmayan bir teste
     * dönüşür ve konektör ayarındaki bir hata kimseye görünmez.
     */
    private static void startConnectOrSkipLocally() {
        try {
            CONNECT.start();
        } catch (RuntimeException e) {
            // GitHub Actions her çalışmada CI=true tanımlar; bu değişkenin varlığı
            // "burada atlama yok" demektir.
            if (System.getenv("CI") != null) {
                throw e;
            }
            Assumptions.abort(
                    "Kafka Connect bu makinede başlatılamadı, test atlandı. CI'da (linux/amd64) "
                            + "çalışır ve orada atlanmaz. Sebep: " + e.getMessage());
        }
    }

    /** Depodaki gerçek ayar dosyasını yükler, yalnızca bağlantı bilgilerini değiştirir. */
    private static void registerConnector() throws Exception {
        String original = Files.readString(CONNECTOR_CONFIG, StandardCharsets.UTF_8);
        String config = replaceOnce(original, "\"database.user\": \"kervan\"",
                "\"database.user\": \"" + POSTGRES.getUsername() + "\"");
        config = replaceOnce(config, "\"database.password\": \"kervan\"",
                "\"database.password\": \"" + POSTGRES.getPassword() + "\"");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://%s:%d/connectors"
                                .formatted(CONNECT.getHost(), CONNECT.getMappedPort(8083))))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(config))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 201 beklenir. Ayar dosyasındaki bir yazım hatası burada 400 döner ve
        // gövde hangi alanın kabul edilmediğini söyler.
        assertThat(response.statusCode())
                .withFailMessage("Konektör kaydedilemedi: %s", response.body())
                .isEqualTo(201);
    }

    /**
     * Metin değişimini yapar ve <b>gerçekten yapıldığını</b> doğrular.
     *
     * <p>Sessizce boşa düşen bir değişim, testi 60 saniye sonra "olay gelmedi" diye
     * kırardı ve hata mesajı yanlış yeri gösterirdi. Ayar dosyası yeniden
     * biçimlendirildiğinde ya da parola değiştiğinde burada, sebebiyle birlikte durur.
     */
    private static String replaceOnce(String text, String target, String replacement) {
        assertThat(text)
                .withFailMessage("Konektör ayarında beklenen metin yok: %s "
                        + "(dosya değiştiyse bu testteki değişimi de güncelleyin)", target)
                .contains(target);
        return text.replace(target, replacement);
    }

    /**
     * Debezium'un replication slot'u açıp akışa başlamasını bekler.
     *
     * <p>{@code snapshot.mode=no_data} olduğu için konektör tabloyu taramaz; yalnızca
     * slot açıldıktan <b>sonraki</b> değişiklikleri görür. Slot hazır olmadan satır
     * yazılırsa o olay hiç yayınlanmaz ve test rastgele kırılırdı.
     *
     * <p>Konektörün REST durumu yerine doğrudan veritabanına bakılıyor: {@code RUNNING}
     * görevin başladığını söyler, slot'un akışa geçtiğini söylemez.
     */
    private static void awaitReplicationSlotStreaming() {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT active FROM pg_replication_slots WHERE slot_name = ?")) {
                statement.setString(1, SLOT_NAME);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() && rows.getBoolean("active");
                }
            }
        });
    }

    private static void insertOutboxRow(String orderId, byte[] payload) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO outbox_messages
                         (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                     VALUES (?, 'Order', ?, 'OrderPlaced', ?, now())
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, orderId);
            statement.setBytes(3, payload);
            statement.executeUpdate();
        }
    }

    private static byte[] avroPayload(String orderId) {
        OrderPlaced event = OrderPlaced.newBuilder()
                .setOrderId(orderId)
                .setCustomerId("c-1")
                .setCurrency("TRY")
                .setTotalAmount(new BigDecimal("249.90"))
                .setItems(List.of(OrderItem.newBuilder()
                        .setProductId("p-1")
                        .setSku("SKU-1")
                        .setQuantity(2)
                        .setUnitPrice(new BigDecimal("124.95"))
                        .build()))
                .setPlacedAt(Instant.parse("2026-03-01T10:15:30Z"))
                .build();

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer()) {
            serializer.configure(Map.of(
                    AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL), false);
            return serializer.serialize(TOPIC, event);
        }
    }

    private static ConsumerRecord<String, OrderPlaced> pollFor(
            KafkaConsumer<String, OrderPlaced> consumer, String key) {
        ConsumerRecords<String, OrderPlaced> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, OrderPlaced> record : records) {
            if (key.equals(record.key())) {
                return record;
            }
        }
        return null;
    }

    private static ConsumerRecord<String, byte[]> pollRawFor(
            KafkaConsumer<String, byte[]> consumer, String key) {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, byte[]> record : records) {
            if (key.equals(record.key())) {
                return record;
            }
        }
        return null;
    }

    private static KafkaConsumer<String, OrderPlaced> avroConsumer() {
        Properties props = baseConsumerProps();
        props.put("value.deserializer", KafkaAvroDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, REGISTRY_URL);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true");
        return new KafkaConsumer<>(props);
    }

    private static KafkaConsumer<String, byte[]> rawConsumer() {
        Properties props = baseConsumerProps();
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static Properties baseConsumerProps() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "cdc-test-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        return props;
    }
}
