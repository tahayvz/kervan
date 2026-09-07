package com.kervan.catalog.infrastructure.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Debezium'un MongoDB change streams'i okuyup katalog değişikliklerini Kafka'ya
 * taşıdığını doğrular.
 *
 * <h2>Sipariş tarafından farkı</h2>
 * order-service'te Debezium bir <b>outbox tablosunu</b> okur: uygulama oraya kendi
 * yazdığı, sürümlü bir domain olayı koyar. Burada öyle bir tablo yok; Debezium
 * doğrudan {@code products} koleksiyonunun değişim akışını okur.
 *
 * <p>Fark bilinçli. Outbox, "şu iş oldu" diyen bir <b>domain olayı</b> yayınlamak
 * içindir ve sözleşmesi {@code event-contracts}'te yazılıdır. Buradaki akış ise
 * verinin kendisinin kopyasıdır: amacı, katalog verisini başka bir yere yansıtmaktır
 * — Faz 5'teki arama indeksi bunun ilk müşterisi olacak. İkisini aynı şey saymak,
 * iç veri modelini dış sözleşme hâline getirmek olurdu.
 *
 * <p>Uygulama bu testte çalışmıyor. Zaten mesele bu: CDC, veriyi yazan servisten
 * habersiz çalışır.
 */
@DisplayName("Debezium CDC: MongoDB change streams -> Kafka")
class CatalogCdcIntegrationTest {

    /** Debezium konu adını {@code <topic.prefix>.<veritabanı>.<koleksiyon>} olarak kurar. */
    private static final String TOPIC = "kervan.catalog.products";
    private static final String DATABASE = "catalog";
    private static final String COLLECTION = "products";
    private static final String CONNECTOR_NAME = "kervan-catalog-products";

    private static final Path CONNECTOR_CONFIG =
            Path.of("../infra/docker/debezium/catalog-products-connector.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Network NETWORK = Network.newNetwork();

    /**
     * Change streams yalnızca replica set modunda çalışır: akış, Mongo'nun oplog'una
     * dayanır ve oplog tek düğümlü kurulumda tutulmaz.
     *
     * <p>Kimlik doğrulama burada kapalı. Testin konusu CDC akışı; parola yönetimini
     * bir kez daha kurmak testi uzatır, doğruladığı şeyi artırmaz. Compose'daki
     * gerçek kurulumda kimlik doğrulama açıktır.
     */
    private static final GenericContainer<?> MONGO =
            new GenericContainer<>(DockerImageName.parse("mongo:7"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("mongodb")
                    .withExposedPorts(27017)
                    .withCommand("--replSet", "rs0", "--bind_ip_all")
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    /** Gerekçesi order-service'teki eşdeğer testte: imaj digest ile sabitlenir. */
    private static final String CONNECT_IMAGE = "quay.io/debezium/connect"
            + "@sha256:6d1458c3a319ef9a30041ae7a78f567aad65d19bc256cd3c19691444cd0e647a";

    private static final GenericContainer<?> CONNECT =
            new GenericContainer<>(DockerImageName.parse(CONNECT_IMAGE))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("connect")
                    .withExposedPorts(8083)
                    .withEnv("BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("GROUP_ID", "kervan-connect-catalog-test")
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
        MONGO.start();
        initiateReplicaSet();
        KAFKA.start();
        startConnectOrSkipLocally();

        registerConnector();
        awaitConnectorRunning();
    }

    @Test
    @DisplayName("koleksiyona eklenen ürün Kafka'ya düşer")
    void insertedProductReachesKafka() {
        String sku = "SKU-" + UUID.randomUUID();

        insertProduct(sku);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));

            ConsumerRecord<String, String> record = await()
                    .atMost(Duration.ofSeconds(60))
                    .until(() -> pollFor(consumer, sku), r -> r != null);

            // Debezium'un change event'i: "after" alanı belgenin değişiklikten
            // sonraki hâlini taşır. capture.mode=change_streams_update_full
            // olmasaydı güncellemelerde yalnızca değişen alanlar gelirdi.
            assertThat(record.value()).contains("\"after\"");
            assertThat(record.value()).contains(sku);
        }
    }

    @Test
    @DisplayName("güncelleme, belgenin tamamıyla birlikte gelir")
    void updatedProductCarriesFullDocument() {
        String sku = "SKU-" + UUID.randomUUID();
        insertProduct(sku);

        try (MongoClient client = mongoClient()) {
            client.getDatabase(DATABASE).getCollection(COLLECTION)
                    .updateOne(new Document("sku", sku),
                            new Document("$set", new Document("name", "Güncellenmiş ad")));
        }

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));

            // Aynı sku için iki olay gelir: ekleme ve güncelleme. Aranan, içinde
            // yeni adı taşıyan olay.
            ConsumerRecord<String, String> record = await()
                    .atMost(Duration.ofSeconds(60))
                    .until(() -> pollForContaining(consumer, "Güncellenmiş ad"), r -> r != null);

            // Güncellemede belgenin TAMAMI geliyor; tüketici eksik alanları
            // bulmak için Mongo'ya geri sormak zorunda kalmıyor.
            assertThat(record.value()).contains(sku);
        }
    }

    private static void initiateReplicaSet() throws Exception {
        // Üye adresi ağ takma adıyla veriliyor: Connect aynı ağda olduğu için
        // "mongodb:27017" adresini çözebilir ve akışa bağlanabilir.
        var result = MONGO.execInContainer("mongosh", "--quiet", "--eval",
                "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongodb:27017'}]})");

        assertThat(result.getExitCode())
                .withFailMessage("Replica set başlatılamadı: %s", result.getStderr())
                .isZero();

        // Birincil düğüm seçilene kadar yazma kabul edilmez.
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            var status = MONGO.execInContainer("mongosh", "--quiet", "--eval",
                    "db.hello().isWritablePrimary");
            return status.getStdout().contains("true");
        });
    }

    private static void startConnectOrSkipLocally() {
        try {
            CONNECT.start();
        } catch (RuntimeException e) {
            if (System.getenv("CI") != null) {
                throw e;
            }
            Assumptions.abort("Kafka Connect bu makinede başlatılamadı, test atlandı. "
                    + "CI'da (linux/amd64) çalışır ve orada atlanmaz. Sebep: " + e.getMessage());
        }
    }

    /** Depodaki gerçek ayar dosyasını yükler, yalnızca bağlantı adresini değiştirir. */
    private static void registerConnector() throws Exception {
        String original = Files.readString(CONNECTOR_CONFIG, StandardCharsets.UTF_8);
        String target = "mongodb://kervan:kervan@mongodb:27017/?replicaSet=rs0&authSource=admin";

        // Değişimin gerçekten yapıldığını doğrula: sessizce boşa düşerse test
        // 60 saniye sonra "olay gelmedi" diye kırılır ve yanlış yeri gösterir.
        assertThat(original)
                .withFailMessage("Konektör ayarında beklenen bağlantı adresi yok: %s", target)
                .contains(target);

        String config = original.replace(target, "mongodb://mongodb:27017/?replicaSet=rs0");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(connectUri("/connectors"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(config))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .withFailMessage("Konektör kaydedilemedi: %s", response.body())
                .isEqualTo(201);
    }

    /**
     * Görev çalışmaya başlayana kadar bekler.
     *
     * <p>{@code snapshot.mode=no_data} olduğu için konektör koleksiyonu taramaz;
     * yalnızca akışa bağlandıktan sonraki değişiklikleri görür. Önce yazılan bir
     * belge hiç yayınlanmaz ve test rastgele kırılırdı.
     */
    private static void awaitConnectorRunning() {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            HttpResponse<String> status = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(connectUri("/connectors/" + CONNECTOR_NAME + "/status"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            // JSON düzgün ayrıştırılıyor: metin bölerek aramak, alanların sırasına
            // bel bağlamak olurdu ve o sıra bir garanti değil.
            JsonNode tasks = JSON.readTree(status.body()).path("tasks");
            return tasks.isArray() && !tasks.isEmpty()
                    && "RUNNING".equals(tasks.get(0).path("state").asText());
        });
    }

    private static URI connectUri(String path) {
        return URI.create("http://%s:%d%s".formatted(
                CONNECT.getHost(), CONNECT.getMappedPort(8083), path));
    }

    private static void insertProduct(String sku) {
        try (MongoClient client = mongoClient()) {
            client.getDatabase(DATABASE).getCollection(COLLECTION).insertOne(
                    new Document("sku", sku)
                            .append("name", "Test ürünü")
                            .append("price", 129.90));
        }
    }

    /**
     * Ana makineden bağlanan istemci. Adreste {@code replicaSet} yok: sürücü tek adres
     * verildiğinde doğrudan bağlanır, üye aramaz. Eklenseydi sürücü üyenin ilan ettiği
     * "mongodb:27017" adresine gitmeye çalışırdı ve o ad Docker ağının dışında
     * çözülmez. catalog-service'in lokal adresi de aynı sebeple sade bırakıldı.
     */
    private static MongoClient mongoClient() {
        return MongoClients.create("mongodb://%s:%d/"
                .formatted(MONGO.getHost(), MONGO.getMappedPort(27017)));
    }

    private static ConsumerRecord<String, String> pollFor(
            KafkaConsumer<String, String> consumer, String needle) {
        return pollForContaining(consumer, needle);
    }

    private static ConsumerRecord<String, String> pollForContaining(
            KafkaConsumer<String, String> consumer, String needle) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
            if (record.value() != null && record.value().contains(needle)) {
                return record;
            }
        }
        return null;
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "catalog-cdc-test-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
