# Kervan — Yol Haritası (Roadmap)

Bu belge, projenin **faz faz** nasıl büyüyeceğini anlatır. Amaç, her fazın sonunda
**çalışan, test edilmiş ve dokümante edilmiş** bir artefakt bırakmaktır — büyük
kurumlarda "incremental delivery" (parça parça teslim) böyle yapılır.

Her fazın altında:
- **Neden** (bu faz hangi kurumsal problemi çözüyor / hangi yetkinliği gösteriyor)
- **Ne inşa edilecek**
- **Kazanım** (bu fazın ortaya koyduğu yetkinlik)

---

## Faz 0 — Temel & Dokümantasyon  ✅

**Neden:** Kod yazmadan önce kapsam, mimari ve kararlar netleşmeli. "Neden bu
teknoloji?" sorusunun yazılı bir cevabı yoksa, karar altı ay sonra savunulamaz.

**Ne inşa edildi:**
- Mono-repo iskeleti (Maven multi-module parent POM)
- `README`, `ARCHITECTURE`, `TECH-RADAR`, `ROADMAP`
- ADR (Architecture Decision Record) çerçevesi + ilk kararlar
- Lokal altyapı için Docker Compose (Postgres ile başlar, her fazda büyür)
- `.gitignore`, `.editorconfig`, kod stili temeli

**Kazanım:** Her mimari karar gerekçesiyle ADR olarak kayıtlı; bağımlılıklar
mono-repo + multi-module Maven ile merkezî olarak yönetiliyor.

---

## Faz 1 — Catalog Service (ilk mikroservis)  ✅

**Neden:** Event-driven'a geçmeden önce **tek bir servisi kusursuz** yapmak gerekir:
temiz katmanlı mimari, migration disiplini, sözleşme öncelikli (API-first) tasarım,
gerçek veritabanına karşı entegrasyon testi.

**Ne inşa edilecek:**
- `catalog-service`: ürün / kategori / marka yönetimi
- **MongoDB** (document / NoSQL) + Spring Data MongoDB — esnek ürün öznitelikleri (ADR-0006)
- **Mongock** (versiyonlu migration / indeks oluşturma — MongoDB'nin Flyway'i)
- REST API + **OpenAPI** (springdoc) — sözleşme dokümante
- Katmanlı mimari (web → application → domain → infrastructure)
- **Testcontainers** ile gerçek MongoDB'ye karşı entegrasyon testi
- Çok aşamalı (multi-stage) **Dockerfile** (küçük, güvenli imaj)
- Global hata yönetimi (RFC 7807 `ProblemDetail`)

**Kazanım:** Katalog MongoDB'de document olarak modellendi — ürün öznitelikleri
kategoriye göre değiştiği için ilişkisel şema EAV anti-desenine zorluyordu. Mongock
ile NoSQL migration'ları versiyonlandı, Testcontainers ile gerçek MongoDB'ye karşı
test edildi, OpenAPI sözleşmesi API-first üretildi.
> Not: İlişkisel dünya ve **Flyway** Faz 4'te gelir (Order/Payment/Inventory → Postgres),
> böylece iki migration aracını (Mongock + Flyway) da göstermiş oluruz.

---

## Faz 2 — API Gateway + Kimlik (Keycloak)  · 2a ✅

**Neden:** Kurumsal sistemlerde her servis kendi auth'unu yazmaz. Merkezî bir
**kimlik sağlayıcı** (Keycloak) ve tek giriş noktası (**Gateway**) olur.

**Ne inşa edilecek:**
- **Spring Cloud Gateway**: routing, merkezî JWT doğrulama, rate-limit köprüsü
- **Keycloak**: OAuth2 / OIDC, realm & client yapılandırması, rol tabanlı yetki
- Servislerde `resource-server` (JWT doğrulama, `@PreAuthorize`)
- Compose'a Keycloak eklenir

**Kazanım:** Kimlik doğrulama Keycloak'a devredilir; token Gateway'de doğrulanıp
claim'ler downstream servislere taşınır, servisler stateless resource-server olur.

---

## Faz 3 — Event-Driven Omurga (Kafka + Avro + Outbox + Debezium)

**Neden:** Bu, projenin **kalbi** ve seni ayrıştıran kısım. Senkron REST çağrıları
servisleri birbirine kilitler (coupling) ve zincirleme hataya (cascading failure)
açar. Event-driven mimari bunu çözer — ama "dual write" problemi (DB + Kafka'ya aynı
anda yazma) ele alınmazsa veri tutarsız kalır.

**Ne inşa edilecek:**
- **Apache Kafka** + **Confluent Schema Registry**
- **Apache Avro** ile şemalı event'ler (geriye/ileriye uyumluluk)
- **Transactional Outbox** deseni: event, iş verisiyle **aynı transaction**'da
  outbox tablosuna yazılır
- **Debezium** (CDC): değişiklikleri okuyup Kafka'ya taşır — "dual write" problemi
  kökten çözülür. **İki kaynak:** Postgres (WAL, outbox tablosu) + MongoDB (change
  streams, Catalog) — aynı desen, iki farklı depo (ADR-0006)
- Ortak `event-contracts` modülü (Avro şemaları tek yerde)

**Kazanım:** Dual-write problemi Transactional Outbox + Debezium CDC ile çözülür;
event'ler Avro + Schema Registry ile şemalanıp geriye dönük uyumluluk garanti edilir.

---

## Faz 4 — Sipariş Akışı + Saga (dağıtık tutarlılık)

**Neden:** Bir siparişte 3 servis (Order, Payment, Inventory) tutarlı olmalı ama
dağıtık transaction (2PC) ölçeklenmez. Çözüm: **Saga** (telafi edici işlemler).

**Ne inşa edilecek:**
- `order-service`, `payment-service`, `inventory-service` — hepsi **PostgreSQL + Flyway**
  (para/stok ACID ister; Faz 1'deki Mongock'un yanına Flyway'i de göstermiş oluruz)
- **Saga (orchestration)**: OrderCreated → ReserveStock → ProcessPayment →
  ConfirmOrder; herhangi bir adım başarısızsa **compensation** (StockReleased,
  PaymentRefunded, OrderCancelled)
- İdempotent tüketici (aynı event iki kez işlenmez)
- Durum makinesi (state machine) ile sipariş yaşam döngüsü

**Kazanım:** Dağıtık transaction yerine orchestration-based Saga kullanılır; her
adımın telafi (compensation) işlemi tanımlanır, idempotency ile tekrarlar etkisizleşir.

---

## Faz 5 — Arama (Elasticsearch) + Cache (Redis)

**Neden:** "Nike, 44 numara, siyah, 1000-2000 TL" gibi çok kriterli aramayı SQL'de
yapmak işkencedir. Ayrıca sık okunan veri her seferinde DB'ye gitmemeli.

**Ne inşa edilecek:**
- `search-service`: Kafka'daki katalog event'lerini dinleyip **Elasticsearch**'e
  indeksler (CQRS okuma modeli)
- Faceted search, filtreleme, sayfalama
- **Redis**: sık okunan katalog verisi için cache, dağıtık **kilit**, **rate-limit**

**Kazanım:** Arama için CQRS okuma modeli kurulur; Kafka'dan beslenen Elasticsearch
indeksiyle faceted search, Redis ile cache ve dağıtık kilit sağlanır.

---

## Faz 6 — Gözlemlenebilirlik (Observability)

**Neden:** Mikroservislerde bir hata 5 servise yayılabilir. "Nerede, kaç ms takıldı?"
sorusuna cevap veremezsen prod'da körsün. Üç ayak: **log, metrik, trace**.

**Ne inşa edilecek:**
- **OpenTelemetry** ile otomatik enstrümantasyon (trace + metrik)
- **Jaeger**: dağıtık trace (istek servisler arası nasıl aktı)
- **Prometheus** + **Grafana**: metrik toplama + dashboard'lar (RED/USE)
- **Loki**: merkezî log; trace-id ile log korelasyonu

**Kazanım:** OpenTelemetry ile uçtan uca trace context propagation sağlanır; bir
isteğin hangi serviste kaç ms harcadığı Jaeger'da izlenir; Grafana'da RED
metrikleriyle alarm kurulur.

---

## Faz 7 — Dayanıklılık (Resilience4j)

**Neden:** Downstream servis yavaşladığında bütün sistem çökmemeli. "Bulkhead",
"circuit breaker", "timeout", "retry" olmadan bir çağrı zinciri dominoyu devirir.

**Ne inşa edilecek:**
- **Resilience4j**: circuit breaker, retry (backoff), time limiter, bulkhead
- Fallback stratejileri
- Redis tabanlı rate limiting (Gateway'de)
- Gerçekten devreye girdiğini gösteren testler (hata enjeksiyonu)

**Kazanım:** Resilience4j ile circuit breaker ve bulkhead uygulanır; Gateway'de
rate limiting; downstream timeout'larda fallback ile graceful degradation.

---

## Faz 8 — CI/CD (GitHub Actions)  ✅ (kısmi: CI tamam, CD planlı)

**Neden:** "Bende çalışıyordu" bir mühendislik cevabı değildir. Her commit otomatik
derlenmeli, test edilmeli, taranmalı ve imaj üretmeli.

**Ne inşa edilecek:**
- **GitHub Actions** pipeline (ücretsiz runner):
  - build + unit + Testcontainers integration test
  - kod kalite/güvenlik: OWASP dependency-check / Trivy imaj taraması
  - Docker imajı build & (opsiyonel) GHCR'a push
- Matrix build, cache, PR gate

**Kazanım:** Her PR'da Testcontainers testleri, bağımlılık güvenlik taraması ve imaj
build eden bir Actions pipeline'ı çalışır; kırmızı build merge edilemez.

---

## Faz 9 — Kubernetes + Helm

**Neden:** Compose lokal içindir; kurumsal deploy Kubernetes'tir. Bir lead
"Spring Boot'u K8s'e nasıl deploy edersin?" sorusuna uygulamalı cevap verebilmeli.

**Ne inşa edilecek:**
- Her servis için **Helm chart** (Deployment, Service, ConfigMap, Secret, Ingress)
- **HPA** (Horizontal Pod Autoscaler), resource limits/requests
- Liveness/readiness probe'ları (Spring Actuator)
- **Kind/Minikube** ile lokal cluster üzerinde uçtan uca çalıştırma

**Kazanım:** Servisler Helm chart'larıyla parametrize edilir; readiness/liveness
probe, HPA ve resource limit'leriyle K8s'e deploy edilir.

---

## Faz sonrası (opsiyonel ileri seviye)

- **Contract testing** (Spring Cloud Contract) — servisler arası sözleşme garantisi
- **Transactional Outbox → Sink** ile analytics pipeline
- **Chaos engineering** (istek/servis öldürme deneyleri)
- **API versioning** stratejisi
- **Multi-tenant** yaklaşımı
- **gRPC** (servis-içi yüksek performanslı senkron çağrılar)

Bu maddeler CV'yi "senior"dan "staff/lead" seviyesine taşır.
