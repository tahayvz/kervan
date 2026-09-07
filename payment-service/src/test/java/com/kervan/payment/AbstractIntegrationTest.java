package com.kervan.payment;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gerçek PostgreSQL ve Kafka'ya karşı çalışan testler için ortak temel.
 *
 * <p>Container'lar bir kez başlatılır ve hiç durdurulmaz; JVM ile aynı ömrü yaşarlar.
 * {@code @Testcontainers} kullanılsaydı container her test sınıfından sonra durur, ama
 * Spring bağlamı sınıflar arasında önbellekte kalırdı — ikinci sınıf ölmüş bir
 * container'ın portunu kullanmaya çalışırdı. Temizliği Ryuk yapar.
 *
 * <p>Schema Registry {@code mock://} ile bellek içinde çalışır: şema kaydı ve
 * çözümleme aynı kodla yürür, yalnızca ağ katmanı devre dışıdır.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final String REGISTRY_URL = "mock://payment-service-tests";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.properties.schema.registry.url", () -> REGISTRY_URL);
        registry.add("spring.kafka.producer.properties.schema.registry.url", () -> REGISTRY_URL);
        registry.add("kervan.schema-registry.url", () -> REGISTRY_URL);
    }
}
