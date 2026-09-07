package com.kervan.search;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gerçek Elasticsearch ve Kafka'ya karşı çalışan testler için ortak temel.
 *
 * <p>Container'lar bir kez başlatılır ve durdurulmaz; Spring bağlamı sınıflar arasında
 * önbellekte kaldığı için durdurulan bir container'ın portu geçersiz kalırdı.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractSearchIntegrationTest {

    /**
     * Elasticsearch 8.13.4'ün <b>linux/amd64</b> sürümü, sindirim (digest) ile
     * sabitlenmiş.
     *
     * <p>Etiket, çalıştığın makinenin mimarisine göre farklı bir imaja çözülür ve
     * ARM sürümündeki Java çalışma zamanı bu makinede daha ilk yerel çağrıda çöküyor
     * ({@code SIGILL ... System.registerNatives ... linux-aarch64}) — Debezium
     * imajıyla birebir aynı arıza. Digest her yerde aynı amd64 imajını seçer: CI'da
     * yerel, Apple Silicon'da emülasyonla.
     *
     * <p>Sürüm yükseltilirken digest de güncellenmelidir:
     * {@code docker manifest inspect docker.elastic.co/elasticsearch/elasticsearch:<sürüm>}
     */
    private static final String ELASTICSEARCH_IMAGE =
            "docker.elastic.co/elasticsearch/elasticsearch"
                    + "@sha256:f455c50fb82017dae23878c1b12fb2188dfb984723d62db2c5bd0c1f78e246f0";

    protected static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(DockerImageName.parse(ELASTICSEARCH_IMAGE)
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                    // Lokal testte güvenlik kapalı: kimlik doğrulama kurmak testin
                    // ölçtüğü şeye bir şey katmaz, yalnızca kurulumu uzatır.
                    .withEnv("xpack.security.enabled", "false")
                    // Tek düğüm; küme sağlığı SARI kalır ve bu beklenen durumdur.
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    // Emülasyon altında açılış yavaş; varsayılan süre yetmiyor.
                    .withStartupTimeout(java.time.Duration.ofMinutes(5));

    /**
     * Container'ların birbirini görebilmesi için ortak ağ.
     *
     * <p>Bu testlerin çoğu Kafka'ya ana makineden bağlanır ve ağa ihtiyaç duymaz. Ama
     * tam zincir testinde Kafka Connect <b>başka bir container'dan</b> bağlanıyor ve
     * Kafka'nın ana makineye ilan ettiği adres ({@code localhost:<port>}) orada
     * Connect'in kendisini gösterir. Ağ takma adı olmadan bağlantı kurulamaz.
     */
    protected static final Network NETWORK = Network.newNetwork();

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    static {
        ELASTICSEARCH.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
