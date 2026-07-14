# Kervan — Yol Haritası (Roadmap)

Bu belge, projenin **faz faz** nasıl büyüyeceğini anlatır. Amaç, her fazın sonunda
**çalışan, test edilmiş ve dokümante edilmiş** bir artefakt bırakmaktır — büyük
kurumlarda "incremental delivery" (parça parça teslim) böyle yapılır.

Her fazın altında:
- **Neden** (bu faz hangi kurumsal problemi çözüyor / hangi yetkinliği gösteriyor)
- **Ne inşa edilecek**
- **Mülakatta karşılığı** (bu fazın sana kazandırdığı konuşma başlığı)

---

## Faz 0 — Temel & Dokümantasyon  ✅

**Neden:** Bir lead'in ilk işi kod yazmak değil; kapsamı, mimariyi ve kararları
netleştirmektir. "Neden bu teknoloji?" sorusuna yazılı cevap veremeyen bir aday
lead olamaz.

**Ne inşa edildi:**
- Mono-repo iskeleti (Maven multi-module parent POM)
- `README`, `ARCHITECTURE`, `TECH-RADAR`, `ROADMAP`
- ADR (Architecture Decision Record) çerçevesi + ilk kararlar
- Lokal altyapı için Docker Compose (Postgres ile başlar, her fazda büyür)
- `.gitignore`, `.editorconfig`, kod stili temeli

**Mülakatta karşılığı:** "Projeyi ADR'lerle yönetiyorum; her mimari karar gerekçesiyle
kayıtlı. Mono-repo + multi-module Maven ile bağımlılıkları merkezî yönetiyorum."

---

## Faz 1 — Catalog Service (ilk mikroservis)

**Neden:** Event-driven'a geçmeden önce **tek bir servisi kusursuz** yapmak gerekir:
temiz katmanlı mimari, migration disiplini, sözleşme öncelikli (API-first) tasarım,
gerçek veritabanına karşı entegrasyon testi.

**Ne inşa edilecek:**
- `catalog-service`: ürün / kategori / marka yönetimi
- PostgreSQL + **Flyway** (versiyonlu şema migration)
- REST API + **OpenAPI** (springdoc) — sözleşme dokümante
- Katmanlı mimari (web → application → domain → infrastructure)
- **Testcontainers** ile gerçek Postgres'e karşı entegrasyon testi
- Çok aşamalı (multi-stage) **Dockerfile** (küçük, güvenli imaj)
- Global hata yönetimi (RFC 7807 `ProblemDetail`)

**Mülakatta karşılığı:** "Testcontainers ile H2 değil gerçek Postgres'e test yazıyorum;
Flyway ile şemayı versiyonluyorum; API-first tasarımla OpenAPI sözleşmesi üretiyorum."

---

## Faz 2 — API Gateway + Kimlik (Keycloak)

**Neden:** Kurumsal sistemlerde her servis kendi auth'unu yazmaz. Merkezî bir
**kimlik sağlayıcı** (Keycloak) ve tek giriş noktası (**Gateway**) olur.

**Ne inşa edilecek:**
- **Spring Cloud Gateway**: routing, merkezî JWT doğrulama, rate-limit köprüsü
- **Keycloak**: OAuth2 / OIDC, realm & client yapılandırması, rol tabanlı yetki
- Servislerde `resource-server` (JWT doğrulama, `@PreAuthorize`)
- Compose'a Keycloak eklenir

**Mülakatta karşılığı:** "Auth'u Keycloak'a devrettim; Gateway'de token doğrulanıp
downstream servislere claim'ler taşınıyor; servisler stateless resource-server."

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
- **Debezium** (CDC): outbox tablosundaki değişiklikleri okuyup Kafka'ya taşır —
  "dual write" problemi kökten çözülür
- Ortak `event-contracts` modülü (Avro şemaları tek yerde)

**Mülakatta karşılığı:** "Dual-write problemini Transactional Outbox + Debezium CDC
ile çözdüm; event'leri Avro + Schema Registry ile şemalayıp uyumluluğu garanti ettim."

---

## Faz 4 — Sipariş Akışı + Saga (dağıtık tutarlılık)

**Neden:** Bir siparişte 3 servis (Order, Payment, Inventory) tutarlı olmalı ama
dağıtık transaction (2PC) ölçeklenmez. Çözüm: **Saga** (telafi edici işlemler).

**Ne inşa edilecek:**
- `order-service`, `payment-service`, `inventory-service`
- **Saga (orchestration)**: OrderCreated → ReserveStock → ProcessPayment →
  ConfirmOrder; herhangi bir adım başarısızsa **compensation** (StockReleased,
  PaymentRefunded, OrderCancelled)
- İdempotent tüketici (aynı event iki kez işlenmez)
- Durum makinesi (state machine) ile sipariş yaşam döngüsü

**Mülakatta karşılığı:** "Dağıtık transaction yerine orchestration-based Saga
kullandım; her adımın compensation'ını tanımlayıp idempotency ile tekrarları elimine ettim."

---

## Faz 5 — Arama (Elasticsearch) + Cache (Redis)

**Neden:** "Nike, 44 numara, siyah, 1000-2000 TL" gibi çok kriterli aramayı SQL'de
yapmak işkencedir. Ayrıca sık okunan veri her seferinde DB'ye gitmemeli.

**Ne inşa edilecek:**
- `search-service`: Kafka'daki katalog event'lerini dinleyip **Elasticsearch**'e
  indeksler (CQRS okuma modeli)
- Faceted search, filtreleme, sayfalama
- **Redis**: sık okunan katalog verisi için cache, dağıtık **kilit**, **rate-limit**

**Mülakatta karşılığı:** "Arama için CQRS okuma modeli kurdum; Kafka'dan beslenen
Elasticsearch indeksi ile faceted search yaptım; Redis ile cache ve dağıtık kilit."

---

## Faz 6 — Gözlemlenebilirlik (Observability)

**Neden:** Mikroservislerde bir hata 5 servise yayılabilir. "Nerede, kaç ms takıldı?"
sorusuna cevap veremezsen prod'da körsün. Üç ayak: **log, metrik, trace**.

**Ne inşa edilecek:**
- **OpenTelemetry** ile otomatik enstrümantasyon (trace + metrik)
- **Jaeger**: dağıtık trace (istek servisler arası nasıl aktı)
- **Prometheus** + **Grafana**: metrik toplama + dashboard'lar (RED/USE)
- **Loki**: merkezî log; trace-id ile log korelasyonu

**Mülakatta karşılığı:** "OTel ile uçtan uca trace context propagation sağladım;
bir isteğin hangi serviste kaç ms harcadığını Jaeger'da görüyorum; Grafana'da
RED metrikleriyle alarm kurdum."

---

## Faz 7 — Dayanıklılık (Resilience4j)

**Neden:** Downstream servis yavaşladığında bütün sistem çökmemeli. "Bulkhead",
"circuit breaker", "timeout", "retry" olmadan bir çağrı zinciri dominoyu devirir.

**Ne inşa edilecek:**
- **Resilience4j**: circuit breaker, retry (backoff), time limiter, bulkhead
- Fallback stratejileri
- Redis tabanlı rate limiting (Gateway'de)
- Gerçekten devreye girdiğini gösteren testler (hata enjeksiyonu)

**Mülakatta karşılığı:** "Resilience4j ile circuit breaker + bulkhead uyguladım;
Gateway'de rate-limit; downstream timeout'larda fallback ile graceful degradation."

---

## Faz 8 — CI/CD (GitHub Actions)

**Neden:** "Bende çalışıyordu" bir mühendislik cevabı değildir. Her commit otomatik
derlenmeli, test edilmeli, taranmalı ve imaj üretmeli.

**Ne inşa edilecek:**
- **GitHub Actions** pipeline (ücretsiz runner):
  - build + unit + Testcontainers integration test
  - kod kalite/güvenlik: OWASP dependency-check / Trivy imaj taraması
  - Docker imajı build & (opsiyonel) GHCR'a push
- Matrix build, cache, PR gate

**Mülakatta karşılığı:** "Her PR'da Testcontainers testleri, bağımlılık güvenlik
taraması ve imaj build eden bir Actions pipeline'ı var; kırmızı build merge edilemez."

---

## Faz 9 — Kubernetes + Helm

**Neden:** Compose lokal içindir; kurumsal deploy Kubernetes'tir. Bir lead
"Spring Boot'u K8s'e nasıl deploy edersin?" sorusuna uygulamalı cevap verebilmeli.

**Ne inşa edilecek:**
- Her servis için **Helm chart** (Deployment, Service, ConfigMap, Secret, Ingress)
- **HPA** (Horizontal Pod Autoscaler), resource limits/requests
- Liveness/readiness probe'ları (Spring Actuator)
- **Kind/Minikube** ile lokal cluster üzerinde uçtan uca çalıştırma

**Mülakatta karşılığı:** "Servisleri Helm chart'larıyla parametrize ettim;
readiness/liveness probe, HPA ve resource limit'lerle K8s'e deploy edilebilir hâle getirdim."

---

## Faz sonrası (opsiyonel ileri seviye)

- **Contract testing** (Spring Cloud Contract) — servisler arası sözleşme garantisi
- **Transactional Outbox → Sink** ile analytics pipeline
- **Chaos engineering** (istek/servis öldürme deneyleri)
- **API versioning** stratejisi
- **Multi-tenant** yaklaşımı
- **gRPC** (servis-içi yüksek performanslı senkron çağrılar)

Bu maddeler CV'yi "senior"dan "staff/lead" seviyesine taşır.
