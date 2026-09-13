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
 * <h2>Kural, konektör dosyasından birebir alındı</h2>
 * {@code infra/docker/debezium/inventory-outbox-connector.json}:
 * <pre>
 *   transforms.outbox.table.field.event.key       = aggregate_id
 *   transforms.outbox.table.field.event.payload   = payload
 *   transforms.outbox.route.topic.replacement     = kervan.inventory.events
 *   transforms.outbox.table.fields.additional.placement = trace_parent:header:traceparent
 * </pre>
 * Yani: anahtar {@code aggregate_id}, değer {@code payload} baytları <b>olduğu
 * gibi</b>, konu sabit, {@code trace_parent} sütunu {@code traceparent} başlığına.
 * Payload zaten Confluent kablo biçiminde (sihirli bayt + şema kimliği) yazılmış;
 * köprü onu çözmez, dokunmaz.
 *
 * <h2>Bu kural ELLE kopyalandı ve kayabilir</h2>
 * Taklidin klasik zayıflığı: taklit ettiği şeyle birlikte güncellenmez. Konektör
 * JSON'u değişirse (başka bir konu, başka bir anahtar alanı) bu sınıf değişmez ve test
 * yine yeşil kalır. Yani konektör yapılandırmasındaki bir hata burada
 * <b>yakalanmaz</b>. Konektör dosyalarına dokunan, bu sınıfa da bakmalı.
 */
final class OutboxBridge {

    private record Source(String jdbcUrl, String topic) {
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

    void add(String jdbcUrl, String topic) {
        sources.add(new Source(jdbcUrl, topic));
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
        try (Connection connection = DriverManager.getConnection(
                source.jdbcUrl(), SagaEnvironment.POSTGRES.getUsername(),
                SagaEnvironment.POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, aggregate_id, payload, trace_parent "
                             + "FROM outbox_messages ORDER BY occurred_at ASC");
             ResultSet rows = statement.executeQuery()) {

            while (rows.next()) {
                String key = source.topic() + "/" + rows.getString("id");
                if (published.contains(key)) {
                    continue;
                }
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        source.topic(), rows.getString("aggregate_id"), rows.getBytes("payload"));

                String traceParent = rows.getString("trace_parent");
                if (traceParent != null) {
                    record.headers().add("traceparent", traceParent.getBytes(StandardCharsets.UTF_8));
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
