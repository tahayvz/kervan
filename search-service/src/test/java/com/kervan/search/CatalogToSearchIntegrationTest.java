package com.kervan.search;

import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.domain.port.ProductIndex;
import com.kervan.search.infrastructure.elasticsearch.ProductIndexInitializer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Zincirin tamamı: MongoDB → Debezium → Kafka → arama indeksi.
 *
 * <h2>Bu test neden var?</h2>
 * Diğer testler değişiklik olayının şeklini <b>elle</b> yazıyor — yani benim
 * varsayımımı test ediyor. Bu akışın yazılı bir sözleşmesi yok: MongoDB tipleri
 * genişletilmiş JSON içinde sarmalanarak gelir ve nasıl sarmalandıkları sürücüye,
 * konektöre ve ayarlara bağlıdır. Varsayım yanlışsa alan sessizce boş kalır; hata
 * vermez, arama sonucu eksik çıkar.
 *
 * <p>Burada gerçek Mongo'ya yazılıyor ve gerçek Debezium'un ürettiği olay okunuyor.
 * Tek doğrulayan test budur.
 */
@DisplayName("Katalogtan aramaya tam zincir")
class CatalogToSearchIntegrationTest extends AbstractSearchIntegrationTest {

    private static final String CONNECTOR_CONFIG_PATH =
            "../infra/docker/debezium/catalog-products-connector.json";

    private static final GenericContainer<?> MONGO =
            new GenericContainer<>(DockerImageName.parse("mongo:7"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("mongodb")
                    .withExposedPorts(27017)
                    .withCommand("--replSet", "rs0", "--bind_ip_all")
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));

    /** Gerekçesi order-service'teki eşdeğerinde: imaj digest ile sabitlenir. */
    private static final String CONNECT_IMAGE = "quay.io/debezium/connect"
            + "@sha256:6d1458c3a319ef9a30041ae7a78f567aad65d19bc256cd3c19691444cd0e647a";

    private static GenericContainer<?> connect;

    @Autowired
    private ProductIndex index;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    @Autowired
    private ProductIndexInitializer indexInitializer;

    @BeforeAll
    static void startChain() throws Exception {
        MONGO.start();
        initiateReplicaSet();

        connect = new GenericContainer<>(DockerImageName.parse(CONNECT_IMAGE))
                .withNetwork(NETWORK)
                .withNetworkAliases("connect")
                .withExposedPorts(8083)
                // Ağ içindeki adres kullanılır. Kafka'nın ana makineye ilan ettiği
                // localhost:<port> burada Connect'in kendisini gösterirdi.
                .withEnv("BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("GROUP_ID", "search-chain-test")
                .withEnv("CONFIG_STORAGE_TOPIC", "_connect_configs")
                .withEnv("OFFSET_STORAGE_TOPIC", "_connect_offsets")
                .withEnv("STATUS_STORAGE_TOPIC", "_connect_status")
                .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
                .waitingFor(Wait.forHttp("/connectors").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(4));

        try {
            connect.start();
        } catch (RuntimeException e) {
            if (System.getenv("CI") != null) {
                throw e;
            }
            Assumptions.abort("Kafka Connect bu makinede başlatılamadı, test atlandı. "
                    + "CI'da atlanmaz. Sebep: " + e.getMessage());
        }

        registerConnector();
    }

    @Test
    @DisplayName("Mongo'ya yazılan ürün aramada çıkar")
    void productWrittenToMongoBecomesSearchable() {
        elasticsearch.indexOps(IndexCoordinates.of("products")).delete();
        indexInitializer.ensureIndex();

        String sku = "SKU-" + UUID.randomUUID();
        insertProduct(sku);

        // Alanların gerçekten dolduğunu tek tek doğruluyoruz: genişletilmiş JSON
        // yanlış okunsaydı ürün yine bulunurdu ama fiyatı ya da markası boş olurdu.
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            elasticsearch.indexOps(IndexCoordinates.of("products")).refresh();
            var result = index.search(new SearchQuery(null, null, null, null, null, 0, 20));

            assertThat(result.items())
                    .filteredOn(item -> sku.equals(item.sku()))
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.name()).isEqualTo("Kablosuz kulaklık");
                        assertThat(item.brand()).isEqualTo("Nike");
                        assertThat(item.categoryPath()).isEqualTo("elektronik/ses");
                        assertThat(item.price()).isEqualByComparingTo("1299.90");
                        assertThat(item.currency()).isEqualTo("TRY");
                        assertThat(item.status()).isEqualTo("ACTIVE");
                        assertThat(item.updatedAt()).isNotNull();
                        assertThat(item.version()).isPositive();
                    });
        });
    }

    private static void initiateReplicaSet() throws Exception {
        var result = MONGO.execInContainer("mongosh", "--quiet", "--eval",
                "rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongodb:27017'}]})");
        assertThat(result.getExitCode())
                .withFailMessage("Replica set başlatılamadı: %s", result.getStderr())
                .isZero();

        await().atMost(Duration.ofSeconds(30)).until(() -> MONGO
                .execInContainer("mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary")
                .getStdout().contains("true"));
    }

    /** Depodaki gerçek konektör ayarını yükler; yalnızca bağlantı adresini değiştirir. */
    private static void registerConnector() throws Exception {
        String original = Files.readString(Path.of(CONNECTOR_CONFIG_PATH), StandardCharsets.UTF_8);
        String target = "mongodb://kervan:kervan@mongodb:27017/?replicaSet=rs0&authSource=admin";

        assertThat(original)
                .withFailMessage("Konektör ayarında beklenen bağlantı adresi yok: %s", target)
                .contains(target);

        String config = original.replace(target, "mongodb://mongodb:27017/?replicaSet=rs0");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://%s:%d/connectors"
                                .formatted(connect.getHost(), connect.getMappedPort(8083))))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(config))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .withFailMessage("Konektör kaydedilemedi: %s", response.body())
                .isEqualTo(201);
    }

    /**
     * Belge, catalog-service'in Spring Data ile yazdığı şekilde kurulur: para
     * {@code Decimal128}, zaman {@code Date}, sürüm {@code Long}. Amaç, çeviri
     * katmanının gerçek tiplerle sınanması.
     */
    private void insertProduct(String sku) {
        try (MongoClient client = MongoClients.create(
                "mongodb://%s:%d/?directConnection=true"
                        .formatted(MONGO.getHost(), MONGO.getMappedPort(27017)))) {

            client.getDatabase("catalog").getCollection("products").insertOne(new Document()
                    .append("sku", sku)
                    .append("name", "Kablosuz kulaklık")
                    .append("description", "Gürültü engelleyici")
                    .append("brand", "Nike")
                    .append("categoryPath", "elektronik/ses")
                    .append("priceAmount", new Decimal128(new BigDecimal("1299.90")))
                    .append("priceCurrency", "TRY")
                    .append("status", "ACTIVE")
                    .append("attributes", new Document("renk", "siyah"))
                    .append("createdAt", Date.from(Instant.now()))
                    .append("updatedAt", Date.from(Instant.now()))
                    .append("version", 1L));
        }
    }
}
