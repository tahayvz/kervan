package com.kervan.order;

import com.kervan.order.security.TestJwtSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gerçek PostgreSQL ve Kafka'ya karşı çalışan testler için ortak temel.
 * <p>
 * Container'lar {@code static} olduğu için tüm test sınıfları arasında bir kez ayağa
 * kalkar. Gömülü/sahte altyapı yerine gerçeği kullanmak, Flyway migration'larının ve
 * Kafka üreticisinin gerçekten çalıştığını doğrular.
 *
 * <p><b>Schema Registry neden container değil?</b> Confluent serileştiricileri
 * {@code mock://} ile başlayan bir adres verildiğinde bellek içi bir kayıt defteri
 * kullanır. Şema kaydı ve kimlik atama aynı kodla yürür, yalnızca ağ katmanı
 * devrededir. Gerçek Registry'ye karşı çalışan doğrulama ayrı bir testtedir:
 * {@code SchemaRegistryCompatibilityIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwtSupport.class)
public abstract class AbstractIntegrationTest {

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
        registry.add("kervan.schema-registry.url", () -> "mock://order-service-tests");
    }
}
