# Kervan Commerce Platform

> Event-driven, çok servisli (microservices) bir e-ticaret backend platformu.
> Trendyol / Hepsiburada / Getir / Ebebek ölçeğindeki ekiplerin kullandığı
> kurumsal desen ve teknolojilerle, **sıfır bütçeyle** (yalnızca açık kaynak) inşa edilir.

**Neden "Kervan"?** Tarihte ticaret kervanları malları duraktan durağa taşırdı.
Bu platformda da bir sipariş; katalog → sepet → sipariş → ödeme → stok → kargo
duraklarını **event**'ler üzerinden geçer. İsim, mimarinin kendisini anlatıyor.

---

## Bu proje ne değildir, ne olmalı?

Bu bir "to-do app" veya CRUD demosu **değil**. Amaç, bir **lead / senior Java**
adayının mülakatta ve sahada karşılaşacağı gerçek kurumsal problemleri —
dağıtık tutarlılık, event-driven iletişim, gözlemlenebilirlik (observability),
dayanıklılık (resilience), CI/CD, container orkestrasyonu — **üretim kalitesinde**
çözerek göstermesidir.

Her teknoloji **bir gerekçeyle** seçilir (bkz. [docs/TECH-RADAR.md](docs/TECH-RADAR.md)).
Her önemli mimari karar **yazılı** kayda geçer (bkz. [docs/adr/](docs/adr/)).
Her commit **ne yaptığını ve neden yaptığını** anlatır.

---

## Mülakatta anlatacağın tek cümlelik özet

> "Java 21 + Spring Boot ile, Kafka üzerinde event-driven çalışan, Saga ve
> Transactional Outbox desenleriyle dağıtık tutarlılığı çözen, Elasticsearch ile
> arama, Redis ile cache, OpenTelemetry + Prometheus + Grafana + Jaeger ile uçtan
> uca gözlemlenebilirlik sağlayan, Keycloak (OAuth2/OIDC) ile korunan, Docker
> Compose'da lokal çalışan ve Kubernetes/Helm ile deploy edilen bir e-ticaret
> platformu tasarladım ve kodladım."

---

## Teknoloji özeti (tamamı açık kaynak / ücretsiz)

| Katman | Teknoloji |
|---|---|
| Dil / Runtime | Java 21 (Virtual Threads), Spring Boot 3.x |
| Servis iletişimi | REST (senkron) + Apache Kafka (asenkron, event-driven) |
| Şema yönetimi | Confluent Schema Registry + Apache Avro |
| CDC | Debezium (PostgreSQL → Kafka) |
| Dağıtık tutarlılık | Transactional Outbox + Saga (orchestration) |
| Veri (işlemsel) | PostgreSQL + Flyway (sipariş/ödeme/stok — ACID) |
| Veri (katalog, NoSQL) | MongoDB + Mongock (esnek ürün öznitelikleri) |
| Arama (NoSQL) | Elasticsearch |
| Cache / kilit / rate-limit (NoSQL) | Redis |
| API Gateway | Spring Cloud Gateway |
| Kimlik / yetki | Keycloak (OAuth2 / OIDC / JWT) |
| Dayanıklılık | Resilience4j (circuit breaker, retry, bulkhead) |
| Gözlemlenebilirlik | OpenTelemetry, Prometheus, Grafana, Jaeger, Loki |
| Test | JUnit 5, Testcontainers, Awaitility, WireMock |
| Container | Docker, Docker Compose |
| Orkestrasyon | Kubernetes + Helm (Kind/Minikube ile lokal) |
| CI/CD | GitHub Actions (ücretsiz runner) |

> Gerekçeler için: [docs/TECH-RADAR.md](docs/TECH-RADAR.md)

---

## Mimari (kuşbakışı)

```
                         ┌──────────────┐
                         │   Keycloak    │  (OAuth2 / OIDC)
                         └──────┬───────┘
                                │ JWT doğrulama
                         ┌──────▼───────┐
   İstemci  ───────────▶ │ API Gateway  │  (Spring Cloud Gateway)
                         └──┬───┬───┬───┘
              ┌─────────────┘   │   └─────────────┐
        ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐
        │  Catalog   │    │    Order     │   │   Search    │
        │ (MongoDB)  │    │  (Postgres)  │   │(Elasticsrch)│
        └─────┬─────┘    └──────┬──────┘   └──────▲──────┘
              │ Outbox           │ Saga             │ indeksleme
              │                  ▼                  │
              │           ┌──────────────┐          │
              └──────────▶│  Apache Kafka │──────────┘
                          │ (+ Schema Reg)│
              ┌───────────┴──────┬────────┴──────────┐
        ┌─────▼─────┐     ┌──────▼──────┐     ┌──────▼──────┐
        │ Inventory  │     │   Payment    │     │Notification │
        │  Service   │     │   Service    │     │  Service    │
        └───────────┘     └──────────────┘     └─────────────┘

  Veri (polyglot): PostgreSQL (işlemsel) · MongoDB (katalog) · Redis · Elasticsearch
  CDC: Debezium (Postgres WAL + MongoDB change streams) → Kafka
  Gözlem: OpenTelemetry → Jaeger (trace) · Prometheus/Grafana (metrik) · Loki (log)
```

Detay: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## Yol haritası (faz faz)

Proje **fazlar** hâlinde ilerler. Her faz kendi içinde çalışır durumda teslim edilir.

| Faz | Konu | Durum |
|---|---|---|
| 0 | Temel, dokümantasyon, mono-repo iskeleti, altyapı compose | ✅ Bu commit |
| 1 | Catalog Service (MongoDB, Mongock, OpenAPI, Testcontainers) | ⏳ |
| 2 | API Gateway + Keycloak (OAuth2/OIDC) | ⏳ |
| 3 | Event-driven: Kafka + Avro + Schema Registry + Outbox + Debezium | ⏳ |
| 4 | Order/Payment/Inventory + Saga (orchestration) | ⏳ |
| 5 | Elasticsearch (arama) + Redis (cache/kilit) | ⏳ |
| 6 | Observability: OpenTelemetry + Prometheus + Grafana + Jaeger | ⏳ |
| 7 | Resilience4j (circuit breaker, retry, bulkhead, rate limit) | ⏳ |
| 8 | CI/CD: GitHub Actions (build, test, image, güvenlik taraması) | ⏳ |
| 9 | Kubernetes + Helm (Kind ile lokal cluster) | ⏳ |

Ayrıntılı yol haritası: [docs/ROADMAP.md](docs/ROADMAP.md)

---

## Hızlı başlangıç (Faz 0)

```bash
# Gereksinim: Java 21, Maven 3.9+, Docker + Docker Compose
git clone <repo>
cd kervan

# Maven yapısı geçerli mi?
mvn -q validate

# Altyapıyı ayağa kaldır (Postgres vs. — servisler sonraki fazlarda gelir)
docker compose -f infra/docker/docker-compose.yml up -d
```

---

## Lisans

Öğrenme / portföy amaçlı. Kullanılan tüm bileşenler açık kaynak lisanslıdır.
