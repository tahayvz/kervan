package com.kervan.catalog;

import com.kervan.catalog.security.TestJwtSupport;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Entegrasyon testleri için taban sınıf.
 *
 * <p><b>Neden Testcontainers?</b> Sahte/embedded bir Mongo değil, <b>gerçek MongoDB</b>
 * (prod'daki sürümün aynısı) Docker'da ayağa kalkar. "Test ettiğin şey = prod'daki şey."
 * Mongock migration'ları da bu gerçek instance üzerinde çalışır, yani indeks/şema kurulumu
 * da testte doğrulanır.
 *
 * <p><b>Konteyner neden elle başlatılıyor?</b> Önce {@code @Testcontainers} +
 * {@code @Container} kullanılıyordu. Tek test sınıfı varken sorun çıkarmadı. İkinci
 * sınıf eklenince testler <em>"Connection refused"</em> vermeye başladı ve sebebi
 * ilk bakışta güvenlik değişikliğiymiş gibi göründü.
 *
 * <p>Gerçek sebep şuydu: {@code @Testcontainers} konteyneri her test SINIFINDAN sonra
 * durdurur. Spring uygulama bağlamı ise sınıflar arasında ÖNBELLEKTE tutulur ve
 * {@code @DynamicPropertySource} yalnızca bağlam kurulurken bir kez okunur. Yani
 * ikinci sınıf çalışırken bağlam hâlâ birinci konteynerin portunu gösteriyordu;
 * o konteyner çoktan ölmüştü.
 *
 * <p>Konteyner burada bir kez başlatılıp hiç durdurulmuyor: JVM ile aynı ömrü
 * yaşıyor, port sabit kalıyor, önbellekteki bağlam doğru yeri gösteriyor. Temizliği
 * Testcontainers'ın Ryuk konteyneri yapar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestJwtSupport.class)
public abstract class AbstractMongoIntegrationTest {

    static final MongoDBContainer MONGO_DB = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    /**
     * Önbellek de gerçek Redis'e karşı çalışır. Sahte bir önbellekle test etmek,
     * serileştirmenin ve TTL'in doğruluğunu hiç sınamazdı — hatanın çıkacağı yer tam
     * olarak orası.
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MONGO_DB.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO_DB::getReplicaSetUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
