package com.kervan.saga;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Debezium'un testteki yerine geçen köprü.
 *
 * <h2>Neden gerekiyor</h2>
 * Servisler arasında bir asimetri var: <b>order-service</b>'in uygulama içi bir
 * {@code OutboxPublisher}'ı var ve outbox satırlarını Kafka'ya kendisi taşıyor.
 * <b>inventory</b> ve <b>payment</b>'ta böyle bir sınıf <b>yok</b>; onların
 * cevaplarını outbox tablosundan Kafka'ya Debezium taşır (ADR-0004).
 *
 * <p>Yani üç servis aynı JVM'de ayağa kalksa bile cevaplar order'a hiç ulaşmaz.
 * Testin bir taşıyıcı sağlaması gerekiyor.
 *
 * <h2>Neden gerçek Debezium değil</h2>
 * Kafka Connect'i teste sokmak zinciri tam kapatırdı, ama iki bedeli var:
 * Debezium imajının ARM sürümü geliştirme makinesinde çöküyor (günlük B20), yani
 * test yerelde hiç koşturulamazdı; ve Connect + konektör kaydı, en önemli testi
 * aynı zamanda en kırılgan test yapardı.
 *
 * <p>Yerine konan şeyin ne olduğu açık olmalı: <b>üç servisin mantığı gerçek</b>,
 * yalnızca CDC taşıması taklit ediliyor — ve taklit edilen parça, bizim kodumuz
 * olmayan tek parça. Debezium'un <em>kendi</em> yapılandırması (konu yönlendirme,
 * {@code traceparent} başlığı, {@code ByteArrayConverter}) burada
 * <b>doğrulanmaz</b>; o, sözleşme testlerinde ve Faz 10'un compose koşusunda
 * doğrulanıyor.
 *
 * <h2>Kural konektör dosyasının KENDİSİNDEN okunuyor</h2>
 * Hedef konu, anahtar sütunu, payload sütunu ve {@code traceparent} eşlemesi
 * {@code infra/docker/debezium/*-outbox-connector.json} dosyasından alınır
 * ({@link ConnectorConfig}). Elle kopyalanmaz.
 *
 * <p>İlk hâlinde kopyalanmıştı ve bu, taklidin klasik zayıflığıydı: taklit, taklit
 * ettiği şeyle birlikte güncellenmez. Dosyada konu adı değişse köprü eskisini
 * kullanmaya devam eder, test yine yeşil kalır ve dosyadaki hata görülmezdi.
 *
 * <p>Payload zaten Confluent kablo biçiminde (sihirli bayt + şema kimliği) yazılmış;
 * köprü onu çözmez, baytları olduğu gibi taşır.
 *
 * <h2>Yine de neyi doğrulamadığı açık olmalı</h2>
 * Köprü, dosyadaki <em>değerleri</em> uygular ama Debezium'un <em>kendisini</em>
 * çalıştırmaz: mantıksal çözümleme (logical decoding), yayın (publication), slot,
 * snapshot davranışı ve dönüştürücü seçimleri burada sınanmaz. Dosyanın geri kalanı
 * {@link OutboxConnectorConfigTest} ile sabitlenir.
 */
final class OutboxBridge {

    private record Source(String jdbcUrl, ConnectorConfig connector) {

        String topic() {
            return connector.fixedTopic();
        }
    }

    private final String bootstrapServers;
    private final List<Source> sources = new ArrayList<>();
    /** Taşınmış satırlar. Debezium da her satırı bir kez yayınlar. */
    private final Set<String> published = new HashSet<>();
    /** Kaynak başına en son basılan hata; aynısını tekrar tekrar basmamak için. */
    private final Map<String, String> lastError = new HashMap<>();

    private ScheduledExecutorService scheduler;
    private Producer<String, byte[]> producer;

    OutboxBridge(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Bir outbox tablosunu, onu taşıyan Debezium konektörünün <b>kendi dosyasıyla</b>
     * kaydeder. Kural elle yazılmaz; dosyadan okunur.
     */
    void add(String jdbcUrl, String connectorFile) {
        sources.add(new Source(jdbcUrl, ConnectorConfig.load(connectorFile)));
    }

    void start() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        // Baytlar OLDUGU GIBI gider. Avro serileştirici kullanılsaydı payload bir kez
        // daha sarılır ve tüketici tarafta çözülemezdi.
        props.put("value.serializer", ByteArraySerializer.class.getName());
        producer = new KafkaProducer<>(props);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-bridge");
            t.setDaemon(true);
            return t;
        });
        // 200ms: Debezium'dan hızlı, testi yavaşlatmayacak kadar seyrek.
        scheduler.scheduleWithFixedDelay(this::pump, 0, 200, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    private void pump() {
        for (Source source : sources) {
            try {
                drain(source);
            } catch (Exception e) {
                // Uygulamalar açılana kadar tablo henüz yoktur; bu beklenen bir
                // durumdur ve her 200ms'de bir ekrana basılmamalı. Ama SESSİZ de
                // kalınmamalı: köprü çalışmazsa test yalnızca zaman aşımına uğrar ve
                // sebebini hiçbir yerde yazmaz.
                //
                // Orta yol: aynı hata tekrarlarken susuyoruz, hata DEĞİŞTİĞİNDE bir
                // satır basıyoruz. Böylece "tablo yok" gürültüsü bir kez görünür,
                // arkasından gelen gerçek bir arıza da görünür.
                reportOnce(source, e);
            }
        }
    }

    private void reportOnce(Source source, Exception e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (!message.equals(lastError.put(source.topic(), message))) {
            System.err.println("[outbox-bridge] " + source.topic() + " -> " + message);
        }
    }

    private void drain(Source source) throws Exception {
        ConnectorConfig c = source.connector();
        try (Connection connection = DriverManager.getConnection(
                source.jdbcUrl(), SagaEnvironment.POSTGRES.getUsername(),
                SagaEnvironment.POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     // Sutun adlari KONEKTOR DOSYASINDAN. Biri dosyada degisip burada
                     // degismeseydi kopru eski sutunu okur, test yine yesil kalir ve
                     // dosyadaki hata gorulmezdi.
                     ("SELECT %s, %s, %s, %s FROM outbox_messages ORDER BY occurred_at ASC")
                             .formatted(c.idColumn(), c.keyColumn(),
                                     c.payloadColumn(), c.traceParentColumn()));
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {
                String key = source.topic() + "/" + rows.getString(c.idColumn());
                if (published.contains(key)) {
                    continue;
                }
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        source.topic(), rows.getString(c.keyColumn()), rows.getBytes(c.payloadColumn()));

                String traceParent = rows.getString(c.traceParentColumn());
                if (traceParent != null) {
                    record.headers().add(c.traceParentHeader(),
                            traceParent.getBytes(StandardCharsets.UTF_8));
                }

                // GONDERIM ONCE DOGRULANIR, SONRA "tasindi" diye isaretlenir.
                //
                // Ters sirada yazilmisti ve sessiz bir veri kaybi uretiyordu: gonderim
                // basarisiz olsa bile satir isaretlenmis oluyor, bir sonraki turda
                // atlaniyor ve o saga cevabi BIR DAHA HIC gonderilmiyordu. Test 60
                // saniye bekleyip ciplak bir ConditionTimeout ile dusuyordu -- Kafka'dan,
                // satirdan, kaybolan mesajdan tek kelime etmeden.
                //
                // send() asenkrondur; donen Future beklenmezse hata FARK EDILMEZ.
                // Burada throughput onemsiz, dogruluk onemli: her satir beklenir.
                producer.send(record).get(10, TimeUnit.SECONDS);
                published.add(key);
            }
        }
    }
}
