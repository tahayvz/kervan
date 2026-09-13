package com.kervan.saga;

import com.kervan.inventory.InventoryServiceApplication;
import com.kervan.order.OrderServiceApplication;
import com.kervan.payment.PaymentServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Üç servisi aynı JVM'de, gerçek PostgreSQL ve gerçek Kafka üzerinde ayağa kaldırır.
 *
 * <h2>Neden bu test var</h2>
 * Her servisin kendi uçtan uca testi vardı, ama hepsinde karşı tarafın cevabı
 * <b>elle</b> yazılıyordu: order testi stok/ödeme cevaplarını kendisi Kafka'ya
 * koyuyor, inventory testi komutu kendisi üretiyordu. Yani her yarım, diğer yarımın
 * taklidine karşı doğrulanmıştı — ve iki taklit birbirini tutuyor olsa bile gerçek
 * iki tarafın tuttuğunu kimse göstermemişti.
 *
 * <p>Burada kanıtlanan şey <b>sıra</b>: sipariş → stok ayır → ayrıldı → ödeme al →
 * sonuç → (gerekirse) stok geri bırak → sipariş kapan. Bu zincir daha önce yalnızca
 * Faz 10'da, compose üzerinde ELLE görülmüştü.
 *
 * <h2>Üç ayrı Spring uygulaması, tek JVM</h2>
 * {@code @SpringBootTest} tek bir bağlam kurar. Burada üç <b>ayrı</b> uygulama
 * gerekiyor; her biri kendi veritabanına, kendi portuna ve kendi tüketici grubuna
 * sahip olmalı. Bu yüzden bağlamlar elle, {@link SpringApplicationBuilder} ile
 * kuruluyor.
 *
 * <p>Konu adları <b>ezilmiyor</b>: her servis kendi {@code application.yml}'indeki
 * adı kullanıyor. Bu bilinçli — adların birbirini tutması da sınanan şeyin parçası.
 * Testte ortak bir değer verseydik, üretimde ayrışmış iki ad burada yine yeşil
 * görünürdü.
 */
final class SagaEnvironment {

    /**
     * Şema kayıt defteri bellek içinde ve <b>üçü de aynı adresi kullanıyor</b>.
     *
     * <p>{@code mock://} kayıt defteri JVM içinde, adres metnine göre paylaşılır.
     * Aynı adres = aynı defter = aynı şema kimlikleri. Farklı adresler verseydik
     * inventory'nin yazdığı kimliği order çözemez ve test, gerçek bir uyumsuzluk
     * yokken kırılırdı.
     */
    private static final String REGISTRY = "mock://saga-e2e";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    /**
     * Ödeme reddi eşiği. Bu tutarın ÜSTÜ reddedilir.
     *
     * <p>Tek açılışta iki yolu da sınayabilmek için var: küçük sipariş onaylanır,
     * büyük sipariş reddedilir ve telafi zinciri çalışır. Alternatif, uygulamaları
     * iki kez ayağa kaldırmaktı.
     */
    static final String DECLINE_ABOVE = "1000.00";

    private static ConfigurableApplicationContext order;
    private static ConfigurableApplicationContext inventory;
    private static ConfigurableApplicationContext payment;
    private static OutboxBridge bridge;

    private SagaEnvironment() {
    }

    static synchronized void start() {
        if (order != null) {
            return;
        }
        POSTGRES.start();
        KAFKA.start();
        createDatabases("orders", "inventory", "payment");

        // Sıra önemli değil: Kubernetes'te de hepsi aynı anda başlar ve yakınsar.
        inventory = boot(InventoryServiceApplication.class, "inventory");
        payment = boot(PaymentServiceApplication.class, "payment");
        order = boot(OrderServiceApplication.class, "orders");

        bridge = new OutboxBridge(KAFKA.getBootstrapServers());
        // Konu adlari burada YAZILMIYOR: kopru onlari konektor dosyasindan okuyor.
        bridge.add(jdbcUrlFor("inventory"), "inventory-outbox-connector.json");
        bridge.add(jdbcUrlFor("payment"), "payment-outbox-connector.json");
        bridge.start();

        Runtime.getRuntime().addShutdownHook(new Thread(SagaEnvironment::stop));
    }

    static synchronized void stop() {
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        close(order);
        close(inventory);
        close(payment);
        order = inventory = payment = null;
    }

    static ConfigurableApplicationContext order() {
        return order;
    }

    static ConfigurableApplicationContext inventory() {
        return inventory;
    }

    private static ConfigurableApplicationContext boot(Class<?> application, String database) {
        // AYARLAR KOMUT SATIRI ARGUMANI OLARAK VERILIYOR ("--" onekiyle).
        //
        // Ilk yazilisinda SpringApplicationBuilder.properties(...) kullanilmisti ve
        // uc uygulama da container'a degil localhost:5432'ye baglanmaya calisti.
        // Sebep: properties(...) bir VARSAYILAN ozellik kaynagi ekler ve varsayilanlar
        // Spring'in oncelik siralamasinda EN ALTTADIR -- servisin kendi
        // application.yml'i onlari ezer. Komut satiri argumanlari ise en ustte.
        //
        // Belirti yaniltiyordu: "Connection to localhost:5432 refused" hatasi ayarin
        // YANLIS oldugunu degil, HIC UYGULANMADIGINI soyluyordu.
        // SERVLET olarak aciliyor, NONE olarak degil.
        //
        // Ilk denemede NONE secilmisti ("test HTTP kullanmiyor, sunucu neden acilsin").
        // Sonuc: inventory hic acilmadi --
        //     No qualifying bean of type 'JwtDecoder' available
        // Cunku Spring'in OAuth2 kaynak sunucusu otomatik yapilandirmasi
        // @ConditionalOnWebApplication(SERVLET)'tir: web tipi NONE iken JwtDecoder
        // HIC URETILMEZ, ama SecurityConfig onu istemeye devam eder.
        //
        // Genel ders: web tipini kapatmak "sunucuyu acma" demek degil, "web'e bagli
        // otomatik yapilandirmalarin hicbirini calistirma" demek. Servis uretimde
        // servlet uygulamasi; testte de oyle acilmali.
        return new SpringApplicationBuilder(application)
                .web(org.springframework.boot.WebApplicationType.SERVLET)
                .run(
                        // Portlar RASTGELE. yml'deki sabit portlar (8082/9082 ...)
                        // gelistirme makinesinde baska bir sey tarafindan tutulmus
                        // olabilir; testin bunu umursamasi icin bir sebep yok.
                        "--server.port=0",
                        "--management.server.port=0",
                        // HER UYGULAMA KENDI application.yml'INE SABITLENIYOR.
                        //
                        // Ilk yazilisinda bu satir yoktu ve inventory diye baslatilan
                        // uygulama `[order-service]` adiyla acildi, order semasini
                        // inventory veritabanina uyguladi.
                        //
                        // Sebep: `classpath:/application.yml` de GLOBAL bir addir.
                        // Uc servisin ucunde de ayni yolda bir dosya var; paylasilan
                        // bir classpath'te Spring ILKINI bulur ve digerlerini hic
                        // gormez. Hicbir hata cikmaz -- yanlis yapilandirmayla acilan
                        // bir uygulama, acilmis bir uygulamadir.
                        //
                        // spring.config.location varsayilan arama yerlerinin yanina
                        // EKLEMEZ, onlarin YERINE gecer; istenen de bu.
                        "--spring.config.location=" + configOf(application),
                        "--spring.datasource.url=" + jdbcUrlFor(database),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--spring.kafka.consumer.properties.schema.registry.url=" + REGISTRY,
                        "--spring.kafka.producer.properties.schema.registry.url=" + REGISTRY,
                        "--kervan.schema-registry.url=" + REGISTRY,
                        "--kervan.payment.simulator.decline-above=" + DECLINE_ABOVE,
                        // İzleme kapalı: açık olsaydı üç uygulama da localhost:4318'e
                        // span göndermeye çalışır ve her biri sürekli bağlantı hatası
                        // basardı. Burada sınanan şey izleme değil.
                        "--management.tracing.enabled=false",
                        // Log yapılandırması JVM GENELİNDE yaşar, bağlam başına değil:
                        // üç uygulama sırayla logback'i yeniden kurar ve sonuncusu
                        // kazanır. Üçünü de aynı dosyaya yazdırmak bu yarışı zararsız
                        // kılıyor; depoya da hiçbir şey düşmüyor.
                        "--logging.file.name=target/saga-e2e.json");
    }

    /**
     * Uygulamanin KENDI derlenmis application.yml'i.
     *
     * <p>Kaynak agacindaki dosya degil {@code target/classes} altindaki kullaniliyor:
     * uygulamanin calisirken gercekten okudugu dosya odur. Ikisi ayni icerikte olsa da
     * "derlenmis olani oku" kurali, bir gun kaynak isleme adimi (filtreleme, profil)
     * araya girdiginde testi dogru tarafta tutar.
     */
    private static String configOf(Class<?> application) {
        String module = switch (application.getSimpleName()) {
            case "OrderServiceApplication" -> "order-service";
            case "InventoryServiceApplication" -> "inventory-service";
            case "PaymentServiceApplication" -> "payment-service";
            default -> throw new IllegalArgumentException("Bilinmeyen uygulama: " + application);
        };
        Path config = Path.of("..", module, "target", "classes", "application.yml")
                .toAbsolutePath().normalize();
        if (!Files.exists(config)) {
            // Acik hata: aksi halde Spring sessizce varsayilanlara duser ve test
            // anlasilmaz bir yerde kirilir.
            throw new IllegalStateException(
                    "Yapilandirma bulunamadi: " + config + " -- once `mvn -pl " + module
                            + " process-resources` gerekiyor olabilir.");
        }
        return "file:" + config;
    }

    private static String jdbcUrlFor(String database) {
        // Container'ın kendi URL'i varsayılan veritabanına bakar; her servis kendi
        // veritabanını kullanmalı. Aynı veritabanını paylaşsalardı üçünün de
        // `outbox_messages` tablosu çakışırdı.
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    }

    private static void createDatabases(String... names) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String name : names) {
                statement.execute("CREATE DATABASE " + name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Test veritabanları oluşturulamadı", e);
        }
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }
}
