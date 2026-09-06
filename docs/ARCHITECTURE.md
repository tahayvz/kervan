# Kervan — Mimari (Architecture)

Bu belge sistemin **yapısını**, **bounded context**'lerini (sınırlanmış alanlar),
**iletişim desenlerini** ve **veri sahipliğini** anlatır. Diyagramlar C4 modelinin
gevşek bir uyarlamasıdır (Context → Container → Component).

---

## 1. Problem alanı (domain)

Kervan bir **e-ticaret** platformudur. Temel iş akışı:

> Müşteri ürünleri **arar** → **sepete** ekler → **sipariş** oluşturur →
> **ödeme** alınır → **stok** düşülür → **kargo/bildirim** tetiklenir.

Bu akış tek bir servise sığdırılamaz; çünkü her adımın **farklı ölçek, farklı
ekip, farklı değişim hızı** vardır. Katalog günde bir güncellenirken, sipariş
saniyede yüzlerce kez yazılır. Bu yüzden **mikroservis** ayrımı yaparız.

---

## 2. Bounded Context'ler (Domain-Driven Design)

Her context **kendi verisine sahiptir** (database-per-service). Başka bir servisin
tablosuna asla doğrudan erişilmez — yalnızca API veya event üzerinden konuşulur.

| Context | Sorumluluk | Veri sahibi (depo) |
|---|---|---|
| **Catalog** | Ürün, kategori, marka, öznitelik | **MongoDB** — `products` koleksiyonu |
| **Inventory** | Stok miktarı, rezervasyon | **PostgreSQL** — stock, reservations |
| **Order** | Sipariş yaşam döngüsü, Saga orkestrasyonu | **PostgreSQL** — orders, order_items, saga_state |
| **Payment** | Ödeme alma / iade | **PostgreSQL** — payments, refunds |
| **Search** | Okuma modeli (CQRS), arama indeksi | **Elasticsearch** index |
| **Notification** | E-posta / SMS / push (simülasyon) | **PostgreSQL** — notifications |
| **Identity** | Kimlik & yetki (Keycloak devreder) | Keycloak realm |

> **İlke:** "Servis sınırı = veri sahipliği sınırı." Paylaşılan veritabanı yoktur.
> Bu, ekiplerin bağımsız deploy edebilmesinin ön koşuludur.

### 2.1 Polyglot persistence (çok-depolu kalıcılık)

Her servis **işine en uygun** veri deposunu seçer — tek bir teknolojiyi her yere
zorlamayız (bkz. ADR-0006):

| Depo | Aile | Nerede & neden |
|---|---|---|
| **PostgreSQL** | İlişkisel (ACID) | Sipariş/ödeme/stok — para ve stok kesin tutarlılık ister |
| **MongoDB** | Document (NoSQL) | Katalog — kategoriye göre değişen esnek öznitelikler |
| **Elasticsearch** | Arama motoru (NoSQL) | Arama okuma modeli — ters indeks, facet, relevance |
| **Redis** | Key-value (NoSQL) | Cache, dağıtık kilit, rate-limit — mikrosaniye erişim |

> **Neden katalog MongoDB?** Ürün öznitelikleri heterojendir (ayakkabı → numara/renk;
> kitap → ISBN/yazar). İlişkisel şemada bu, ya yüzlerce nullable kolon ya EAV
> anti-deseni doğurur. Belge modeli bu esnekliği doğal karşılar.
>
> **Neden para/stok Postgres?** Finansal doğruluk ve stok bütünlüğü için ACID
> transaction ve ilişkisel garantiler şarttır — burada esneklik değil kesinlik önceliklidir.

---

## 3. İletişim desenleri

İki tür iletişim vardır ve **hangisinin nerede** kullanılacağı bir mimari karardır:

### 3.1 Senkron (REST) — "şimdi cevap lazım"
- İstemci → Gateway → servis (örn. ürün detayı getir)
- Servis → servis **mümkün olduğunca kaçınılır** (coupling & cascading failure riski)
- Kullanıldığında **Resilience4j** ile korunur (timeout, circuit breaker)

### 3.2 Asenkron (Event / Kafka) — "olan biteni haber ver"
- Bir context durum değiştirdiğinde **event** yayınlar (örn. `OrderCreated`)
- İlgilenen contextler **kendi hızında** tüketir
- **Gevşek bağ (loose coupling):** Order servisi, kimin dinlediğini bilmez
- Event'ler **Avro + Schema Registry** ile şemalıdır (uyumluluk garantisi)

> **Bugünkü durum:** Avro ve Schema Registry kullanımda (ADR-0008). Şemalar ortak
> `event-contracts` modülünde; uyumluluk modu BACKWARD. Outbox kaydını Kafka'ya
> taşıyan iş şu an uygulama içindeki bir yayıncıdır; Debezium'a geçiş Faz 3c'dedir
> (§4.2).

```
Senkron  : İstemci ──REST──▶ Gateway ──REST──▶ Catalog   (anlık cevap)
Asenkron : Order ──event──▶ Kafka ──▶ {Inventory, Payment, Search, Notification}
```

---

## 4. Dağıtık tutarlılık (bu projenin kalbi)

### 4.1 Dual-write problemi
Bir servis hem kendi DB'sine yazıp hem Kafka'ya event göndermek ister. İkisi ayrı
sistem olduğundan **atomik değildir**: DB commit olur ama Kafka publish patlarsa
event kaybolur (ya da tam tersi). Sonuç: **tutarsız veri**.

### 4.2 Çözüm: Transactional Outbox + Debezium (CDC)
```
[Order Service]
   BEGIN TX
     INSERT orders (...)
     INSERT outbox (event=OrderCreated, payload=...)   ← aynı transaction
   COMMIT
        │
        │ (Debezium PostgreSQL WAL'ı okur)
        ▼
   [Debezium CDC] ──▶ [Kafka topic: order.events]
```
İş verisi ve event **aynı transaction**'da yazılır → ya ikisi de olur ya hiçbiri.
Debezium, DB'nin **write-ahead log**'unu (WAL) okuyup outbox kayıtlarını Kafka'ya
taşır. Böylece uygulama kodu Kafka'ya hiç dokunmaz; kayıp/çift yazma imkânsızlaşır.

> **Çok-kaynaklı CDC:** Debezium yalnızca Postgres'e özgü değildir. Postgres için
> **WAL**, MongoDB için **change streams** (oplog) okur. Polyglot persistence (ADR-0006)
> nedeniyle projede iki kaynak da vardır: işlemsel servisler (Order/Payment/Inventory)
> Postgres WAL'ından, Catalog ise MongoDB change streams'ten Kafka'ya akar. Aynı desen,
> iki farklı depo. *(MongoDB change streams için Mongo replica set modunda çalışmalı.)*

### 4.3 Saga (çoklu servis tutarlılığı)
Sipariş akışı birden çok servisi kapsar; 2PC (two-phase commit) ölçeklenmez.
Bunun yerine **orchestration-based Saga**:

```
OrderCreated
   ├─▶ ReserveStock ──ok──▶ ProcessPayment ──ok──▶ ConfirmOrder ✅
   │                              │
   │                              └──fail──▶ ReleaseStock ─▶ CancelOrder (compensation)
   └──fail──▶ CancelOrder (compensation)
```
Her ileri adımın bir **telafi (compensation)** adımı vardır. Order servisi
orkestratördür; durumu `saga_state` tablosunda tutar. Tüketiciler **idempotent**'tir
(aynı event iki kez gelse tek kez işlenir).

---

## 5. CQRS (okuma/yazma ayrımı) — Arama örneği

Katalog **yazma modeli** (MongoDB) ile **okuma modeli** (Elasticsearch) ayrılır:

```
Catalog(MongoDB, yazma) ──event──▶ Kafka ──▶ Search Service ──▶ Elasticsearch(okuma)
```
Yazma tarafı normalize/tutarlı; okuma tarafı arama için denormalize/hızlı. İkisi
**eventual consistency** ile senkron kalır. Bu, e-ticaret aramasının standart desenidir.

---

## 6. Katman mimarisi (her servisin içi)

Her mikroservis kendi içinde temiz katmanlara ayrılır (hexagonal'a yakın):

```
web (controller, DTO)          ← dış dünya (HTTP)
  └─ application (use-case,     ← iş akışı orkestrasyonu, transaction sınırı
        service, port)
       └─ domain (entity,       ← saf iş kuralları, framework'süz
            value object)
            └─ infrastructure   ← DB, Kafka, dış servis adaptörleri
               (adapter, repo)
```
**Kural:** Bağımlılıklar içe doğru akar. `domain` hiçbir framework'e bağımlı değildir;
bu sayede iş kuralları test edilebilir ve teknoloji değişikliğine dayanıklıdır.

---

## 7. Gözlemlenebilirlik (observability)

Üç ayak, tek korelasyon anahtarı (**trace-id**) ile bağlanır:

| Ayak | Araç | Ne cevaplar? |
|---|---|---|
| Trace | OpenTelemetry + Jaeger | İstek servisler arası nasıl aktı, nerede kaç ms? |
| Metrik | Prometheus + Grafana | Hız, hata oranı, doygunluk (RED/USE)? |
| Log | Loki | Ne oldu, hangi trace-id ile? |

OTel context propagation sayesinde bir istek Gateway'den girip 4 servisi dolaşsa
bile **tek bir trace** olarak görünür.

---

## 8. Kesitsel (cross-cutting) kararlar

- **API-first:** Sözleşme (OpenAPI/Avro) koddan önce gelir.
- **12-Factor:** Config ortam değişkeninden; stateless servisler; log stdout'a.
- **Güvenlik:** Merkezî auth (Keycloak), servisler stateless resource-server.
- **Idempotency:** Tüm event tüketicileri ve ödeme çağrıları idempotent.
- **Migration:** Şema/veri değişimi versiyonlu ve ileriye uyumlu — Postgres'te **Flyway**,
  MongoDB'de **Mongock**. Elle şema değişikliği yasak.
- **Container:** Multi-stage build, non-root user, distroless/temurin slim imaj.

---

## 9. Fiziksel görünüm (deployment)

- **Lokal:** Docker Compose — tüm altyapı + servisler tek makinede.
- **Kurumsal hedef:** Kubernetes + Helm — her servis ayrı Deployment, HPA ile
  otomatik ölçek, ConfigMap/Secret ile config, Ingress ile giriş.

Detaylı kararların gerekçeleri: [adr/](adr/) · Teknoloji seçimleri: [TECH-RADAR.md](TECH-RADAR.md)
