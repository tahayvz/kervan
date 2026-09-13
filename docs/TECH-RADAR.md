# Kervan — Teknoloji Radarı (Tech Radar)

Bir lead'in en çok sorulduğu soru: **"Neden bunu seçtin?"**
Bu belge her teknolojiyi (1) **hangi problemi çözdüğü**, (2) **neden bunu**,
(3) **hangi alternatifi neden elediği** ile açıklar.

> Kural: Hiçbir teknoloji "popüler olduğu için" seçilmedi. Her biri somut bir
> ihtiyaca cevaptır ve tamamı **açık kaynak / ücretsiz**tir (0 bütçe).

---

## Dil & Framework

### Java 21
- **Problem:** Kurumsal backend için olgun, tip-güvenli, yüksek performanslı runtime.
- **Neden:** LTS sürüm. **Virtual Threads** (Project Loom) ile I/O yoğun servislerde
  thread-per-request modelini reaktif karmaşasına girmeden ölçekler. Records,
  pattern matching, sealed types ile daha az boilerplate.
- **Alternatif elenmesi:** Kotlin'in asıl kazancı boilerplate azaltmaktı; Java 21
  records + pattern matching + sealed types ile bunun büyük kısmını zaten veriyor,
  geriye kalan kazanç ikinci bir dil katmanının bedelini karşılamıyor. Go/Node'da bu
  projenin dayandığı bütünleşik yığın (Data + Security + Gateway + Micrometer aynı
  ekosistemde) yok.

### Spring Boot 3.x
- **Problem:** Servisleri hızlı, standart ve üretime hazır kurmak.
- **Neden:** Otomatik yapılandırma, Actuator (sağlık/metrik), ve bu projenin ihtiyaç
  duyduğu her parçanın (Data, Security, Cloud Gateway, Micrometer/OTel köprüsü) aynı
  ekosistemde hazır olması.
- **Alternatif:** Quarkus/Micronaut daha hızlı açılır ve daha az bellek kullanır.
  Bedeli: burada kullanılan entegrasyonların bir kısmı ya yok ya daha az olgun —
  kazanç açılış süresinde, kayıp entegrasyon yüzeyinde.

---

## Servisler Arası İletişim

### Apache Kafka
- **Problem:** Servisleri gevşek bağlamak; event-driven omurga; yüksek hacimli,
  dayanıklı, tekrar-oynatılabilir (replayable) mesajlaşma.
- **Neden:** Log-tabanlı; mesajlar diskte kalır, tüketiciler kendi offset'ini yönetir,
  event **yeniden oynatılabilir** (yeni bir servis geçmiş event'leri baştan işleyebilir).
  Yüksek throughput, partition ile yatay ölçek.
- **Alternatif elenmesi:** RabbitMQ mükemmel bir **message broker** ama **event
  streaming** (kalıcılık, replay, log compaction, CDC hedefi) için Kafka daha uygun.
  *(Bkz. ADR-0003 — ikisinin farkı ve neden Kafka.)*

### Confluent Schema Registry + Apache Avro
- **Problem:** Event şeması zamanla değişir. Üretici yeni alan eklerse eski tüketiciler
  patlamamalı. Şemasız JSON event'ler prod'da "sessiz uyumsuzluk" felaketi doğurur.
- **Neden:** Avro kompakt binary + şema evrimi kuralları; Schema Registry şemaları
  merkezî tutup **backward/forward compatibility** kontrolü yapar. Üretici uyumsuz
  şema publish edemez.
- **Alternatif:** Protobuf de iyi; Avro Kafka/Confluent ekosisteminde daha yerleşik.
- **Durum:** Kullanımda. Şemalar `event-contracts` modülünde; uyumluluk modu BACKWARD.
  Registry'nin kuralı ayrıca test zamanında da doğrulanıyor (`SchemaEvolutionTest`),
  böylece uyumsuz değişiklik çalışan sisteme değil CI'ya çarpıyor. *(ADR-0008)*

### Debezium (Change Data Capture)
- **Problem:** "Dual-write" — DB'ye yaz + Kafka'ya yaz atomik değil (bkz. Architecture §4).
- **Neden:** DB'nin write-ahead log'unu (Postgres WAL) okuyup değişiklikleri Kafka'ya
  taşır. Uygulama Kafka'ya hiç dokunmaz; **Transactional Outbox** deseniyle birlikte
  event kaybını/çiftlenmesini kökten çözer.
- **Alternatif:** Uygulama içi "publish after commit" — race condition ve kayıp riski.

---

## Veri Katmanı — Polyglot Persistence

> **İlke (ADR-0006):** Tek bir veri teknolojisi her yere zorlanmaz. Her servis işine
> en uygun deposu seçer. Bu proje **4 veri ailesi** kullanır: ilişkisel (PostgreSQL),
> document (MongoDB), arama (Elasticsearch), key-value (Redis). Sonuncu üçü **NoSQL**
> ailelerindendir — yani proje "birincil NoSQL deposu" (MongoDB) dâhil geniş bir yelpaze taşır.

### PostgreSQL — işlemsel çekirdek
- **Problem:** Sipariş/ödeme/stok gibi **para ve tutarlılık kritik** veriler için
  transaction'lı, ilişkisel, güvenilir yazma deposu.
- **Neden:** Açık kaynak, ACID, JSONB, olgun; Debezium ile **logical replication**
  desteği (CDC için şart). Order/Payment/Inventory servisleri bunu kullanır; her biri
  **kendi** veritabanına sahip.
- **Alternatif:** MySQL de olur; Postgres'in logical decoding'i Debezium için daha temiz.

### MongoDB — katalog (document / NoSQL)
- **Problem:** Ürün öznitelikleri **heterojen ve şema-esnek**: ayakkabı → numara/renk;
  kitap → ISBN/yazar/sayfa; telefon → RAM/ekran/pil. İlişkisel şemada bu, ya yüzlerce
  nullable kolon ya **EAV anti-deseni** doğurur — sorgusu ve bakımı acı verir.
- **Neden:** Document model bu esnekliği doğal karşılar; her ürün kendi alanlarıyla bir
  belge. Zengin sorgu/indeksleme, yatay ölçek (sharding). Debezium **MongoDB connector**
  ile change streams üzerinden CDC → Kafka mümkün (Catalog → Search akışının kaynağı).
- **Alternatif elenmesi:** Postgres + `jsonb` de olurdu (tek teknoloji sadeliği) ama
  kategori-bazlı zengin sorgu/indeksleme ve belge modelleme MongoDB'de daha doğal;
  ayrıca proje veri ailelerinin dördünü de göstermeyi hedefliyor. *(Bkz. ADR-0006.)*

### Mongock — MongoDB migration
- **Problem:** MongoDB "schema-less" olsa da veri/indeks değişiklikleri **versiyonlu ve
  tekrarlanabilir** olmalı; elle `mongosh` komutu ortamlar arası tutarsızlık demektir.
- **Neden:** Mongock, MongoDB için Flyway/Liquibase muadili: kod-tabanlı, sıralı,
  bir-kez-çalışan changeset'ler; Spring Boot ile entegre; indeks oluşturma/veri backfill
  için idealdir.
- **Not:** Şemasız depo, "migration gerekmez" demek değildir. İndeksler ve veri
  dönüşümleri yine sıralı, bir kez çalışan ve versiyonlanmış olmalı.

### Flyway
- **Problem:** Şema değişikliğini elle yapmak = ortamlar arası tutarsızlık = felaket.
- **Neden:** Versiyonlu, tekrarlanabilir, ileriye-uyumlu migration; her ortam aynı
  şemaya deterministik ulaşır. CI'da otomatik uygulanır.
- **Alternatif:** Liquibase (XML/YAML) — Flyway'in düz SQL yaklaşımı daha şeffaf.

### Redis
- **Problem:** (1) Sık okunan veriyi her seferinde DB'den çekmek pahalı, (2) dağıtık
  ortamda kilit gerekir, (3) rate-limit sayaçları hızlı olmalı.
- **Neden:** In-memory, mikrosaniye erişim; cache + dağıtık kilit (SETNX/Redlock) +
  rate-limit (token bucket) + oturum — hepsi tek araçta.

### Elasticsearch
- **Problem:** Çok kriterli, tam-metin, faceted arama. "44 numara siyah Nike 1000-2000 TL"
  SQL `LIKE` ile milisaniyede yapılamaz.
- **Neden:** Ters indeks (inverted index) ile tam-metin arama, facet/aggregation,
  relevance scoring, typo tolerance. CQRS okuma modeli olarak Kafka'dan beslenir.
- **Alternatif:** OpenSearch (Elasticsearch fork'u, tamamen açık lisans) — kolayca
  geçilebilir; API büyük oranda uyumlu.

---

## Giriş & Güvenlik

### Spring Cloud Gateway
- **Problem:** İstemci 6 servisin adresini bilmemeli; auth, rate-limit, routing
  tek yerde olmalı.
- **Neden:** Reaktif, non-blocking API gateway; route yapılandırma, JWT doğrulama
  filtresi, Redis rate-limiter entegrasyonu, circuit breaker köprüsü.
- **Alternatif:** Kong/Nginx/Apigee güçlü ama Spring ekosisteminde Cloud Gateway
  kod ve config olarak en entegre.

### Keycloak (OAuth2 / OIDC)
- **Problem:** Her servis kendi login/şifre/token mantığını yazmamalı.
- **Neden:** Açık kaynak, olgun IAM; OAuth2/OIDC, realm/rol/client, token issuance,
  social login. Servisler stateless **resource-server** olarak sadece JWT doğrular.
- **Alternatif:** Auth0/Okta (SaaS, ücretli). Keycloak self-hosted ve **ücretsiz**.

---

## Dayanıklılık & Gözlem

### Resilience4j
- **Problem:** Bir downstream servis yavaşlarsa çağrı zinciri bütün sistemi kilitler.
- **Neden:** Hafif, fonksiyonel; circuit breaker, retry (exponential backoff),
  time limiter, bulkhead, rate limiter — Micrometer ile metrik verir.
- **Alternatif:** Netflix Hystrix — **kullanımdan kalktı (deprecated)**; Resilience4j onun halefi.

### OpenTelemetry + Jaeger  ✅
- **Problem:** Dağıtık sistemde "istek nerede takıldı?" görünmez.
- **Neden:** OTel **vendor-nötr** standart; Jaeger trace'leri görselleştirir.
- **Nasıl:** Enstrümantasyon kod içinde — `micrometer-tracing-bridge-otel` +
  OTLP dışa aktarıcı. Span'ler bir **OTel Collector**'a gider, oradan Jaeger'a.
- **Alternatif:** Zipkin (daha basit) — OTel ekosistemi daha geniş ve gelecek-güvenli.
- **Alternatif:** OpenTelemetry Java **ajanı** (`-javaagent`) — kapsamı daha geniş
  ama yalnızca gerçek çalıştırmada var olduğu için CI'da doğrulanamaz. Elendi;
  gerekçe ADR-0012'de.

### Prometheus + Grafana + Loki  ✅
- **Problem:** Metrik toplama, dashboard, alarm ve merkezî log.
- **Neden:** Prometheus **pull** tabanlıdır: uygulama bir uç açar, Prometheus okur.
  Gönderen taraf olsaydı, Prometheus kapalıyken uygulamanın "biriktir mi, at mı,
  bekle mi" sorusunu çözmesi gerekirdi. Ayrıca `up` metriği "hedef cevap vermiyor"
  sinyalini bedava verir. Grafana görselleştirir; panolar ve veri kaynağı **kodda**
  tanımlı. Loki log'u aynı ekosistemde toplar: log METNINI değil yalnızca
  **etiketleri** indeksler, bu yüzden Elasticsearch'ten çok daha ucuzdur; bedeli
  sorgunun önce etiketle daraltılması gerekmesidir. Taşımayı **Grafana Alloy**
  yapar (Promtail kullanımdan kalktı); uygulama Loki'yi tanımaz (ADR-0015).
- **Alternatif:** Push tabanlı OTLP metrik — izlerle tutarlı olurdu ama gönderim
  başarısızlığı, kuyruk ve geri basınç her servise girerdi (ADR-0014).
- **Alternatif:** ELK stack — Loki daha hafif ve Grafana ile daha entegre.

---

## Test & Kalite

### JUnit 5 + Testcontainers
- **Problem:** H2 ile test etmek "gerçek Postgres"i temsil etmez (dialect farkları,
  Debezium/logical replication yok). Prod'da patlar.
- **Neden:** Testcontainers, testte **gerçek** Postgres/Kafka/Elasticsearch'i Docker'da
  ayağa kaldırır. "Test ettiğin şey prod'daki şey" olur.

### WireMock / Awaitility
- **WireMock:** Dış HTTP servisleri deterministik taklit etmek (contract testleri).
- **Awaitility:** Asenkron (event-driven) akışları test etmek — "şu event 5 sn içinde
  gelmeli" gibi koşulları temiz ifade eder.

---

## Paketleme & Dağıtım

### Docker + Docker Compose
- **Problem:** "Bende çalışıyor" ortam farkını yok etmek; lokalde tüm yığını kaldırmak.
- **Neden:** Multi-stage build ile küçük/güvenli imaj; Compose ile tek komutta
  altyapı + servisler.

### Kubernetes + Helm
- **Problem:** Kurumsal ölçek/deploy/otomatik iyileşme/otomatik ölçek.
- **Neden:** K8s fiili orkestrasyon standardı; Helm ile chart'lar parametrize edilir
  (Deployment, Service, ConfigMap, Secret, Ingress, HPA). Lokal doğrulama için Kind.

### GitHub Actions
- **Problem:** Her commit otomatik build/test/tarama/imaj.
- **Neden:** Repoya gömülü, **ücretsiz** runner, geniş action ekosistemi; Testcontainers
  ile uyumlu.
- **Alternatif:** Jenkins/GitLab CI — Actions 0 altyapı ve 0 bütçe ile başlar.

---

## Radar özeti

| Halka | Anlam | Örnekler |
|---|---|---|
| **ADOPT** | Bu projede kesin kullanılıyor | Java 21, Spring Boot, Kafka, PostgreSQL, **MongoDB**, Redis, Elasticsearch, Testcontainers, Docker |
| **TRIAL** | Kullanılıyor, ekipçe derinleşilecek | Debezium, Avro/Schema Registry, **Mongock**, OTel, Resilience4j, Helm |
| **ASSESS** | Faz-sonrası değerlendirilecek | **Cassandra (wide-column, yazma-yoğun)**, Spring Cloud Contract, gRPC, OpenSearch, chaos testing |
| **HOLD** | Bilerek kaçınılan | Hystrix (deprecated), servis-servis senkron zincir, paylaşılan DB |
