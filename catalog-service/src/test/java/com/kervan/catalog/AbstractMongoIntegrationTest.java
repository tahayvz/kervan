package com.kervan.catalog;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Entegrasyon testleri için taban sınıf.
 * <p>
 * <b>Neden Testcontainers?</b> Sahte/embedded bir Mongo değil, <b>gerçek MongoDB</b>
 * (prod'daki sürümün aynısı) Docker'da ayağa kalkar. "Test ettiğin şey = prod'daki şey."
 * Mongock migration'ları da bu gerçek instance üzerinde çalışır, yani indeks/şema kurulumu
 * da testte doğrulanır.
 * <p>
 * Konteyner {@code static} olduğundan tüm test sınıflarında bir kez başlar (hızlı).
 * URI'yi {@code @DynamicPropertySource} ile Spring'e enjekte ederiz.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractMongoIntegrationTest {

    @Container
    static final MongoDBContainer MONGO_DB = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO_DB::getReplicaSetUrl);
    }
}
