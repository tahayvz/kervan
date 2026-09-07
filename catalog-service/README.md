# Catalog Service

Kervan platformunun ürün kataloğu mikroservisi. **MongoDB** (document store) üzerinde,
esnek ürün öznitelikleriyle çalışır (bkz. [ADR-0006](../docs/adr/0006-polyglot-persistence-mongodb.md)).

## Neden MongoDB?
Ürün öznitelikleri kategoriye göre değişkendir (ayakkabı → numara/renk; telefon →
RAM/ekran). Bu heterojenliği ilişkisel şemada tutmak EAV anti-desenine yol açar;
document model doğal çözümdür.

## Mimari (katmanlar — hexagonal / ports & adapters)
```
web            → REST controller, DTO, RFC 7807 hata yönetimi
application    → use-case orkestrasyonu (ProductService), komutlar
domain         → saf iş kuralları (Product, Money) + portlar (ProductRepository)
infrastructure → MongoDB adaptörü, Mongock migration, OpenAPI config
```
Bağımlılıklar **içe** doğru akar; `domain` hiçbir framework'e bağımlı değildir.

## Öne çıkanlar
- **Spring Data MongoDB** — document kalıcılık
- **Mongock** — versiyonlu indeks migration'ı (Flyway'in NoSQL muadili)
- **Optimistic locking** (`@Version`) — eşzamanlı güncelleme çakışmasını yakalar
- **RFC 7807 ProblemDetail** — standart hata gövdesi
- **OpenAPI** — `/swagger-ui.html`, `/v3/api-docs`
- **Testcontainers** — gerçek MongoDB'ye karşı entegrasyon testi

## API (v1)
| Method | Path | Açıklama |
|---|---|---|
| POST | `/api/v1/products` | Ürün oluştur (201, DRAFT) |
| GET | `/api/v1/products/{id}` | Ürün getir |
| GET | `/api/v1/products?category=&status=&page=&size=` | Listele (filtre + sayfalama) |
| PUT | `/api/v1/products/{id}` | Detay güncelle |
| POST | `/api/v1/products/{id}/activate` | Yayına al (ACTIVE) |
| POST | `/api/v1/products/{id}/archive` | Arşivle (ARCHIVED) |
| DELETE | `/api/v1/products/{id}` | Sil (204) |

## Çalıştırma (lokal)
```bash
# 1) MongoDB'yi ayağa kaldır (repo kökünden). mongo-init replica set'i bir kez başlatır.
docker compose -f infra/docker/docker-compose.yml up -d mongodb mongo-init

# 2) Servisi çalıştır
mvn -pl catalog-service spring-boot:run
# → http://localhost:8081/swagger-ui.html
```

## Değişiklik akışı (CDC)

`products` koleksiyonundaki her değişiklik Debezium tarafından okunup
`kervan.catalog.products` konusuna yazılır. Bu servis Kafka'ya **hiç dokunmaz**;
akış onun haberi olmadan çalışır.

Bu, sipariş tarafındaki outbox deseninden farklıdır ve fark bilinçlidir. Outbox bir
**iş olayı** duyurur, sözleşmesi `event-contracts`'te yazılıdır. Buradaki akış ise
verinin **yansımasıdır**: taşınan şey belgenin kendisidir. İlk müşterisi Faz 5'teki
arama indeksi olacak.

Ayrıntı ve kurulum: [`infra/docker/debezium/README.md`](../infra/docker/debezium/README.md)

**Mongo neden replica set modunda?** Change streams, Mongo'nun oplog'una dayanır ve
oplog tek düğümlü kurulumda tutulmaz. Küme kurmak için değil, yalnızca bu yüzden.
Bu değişiklik servisin bağlantı adresini etkilemedi; sebebi `application.yml` içinde
yazılı.

## Test
```bash
# Unit + Testcontainers entegrasyon testleri (Docker gerekir)
mvn -pl catalog-service test
```

Testlerden biri (`CatalogCdcIntegrationTest`) MongoDB, Kafka ve Kafka Connect
container'larını ayağa kaldırır, depodaki gerçek konektör ayar dosyasını yükler ve
yalnızca koleksiyona belge yazarak olayın konuya düşmesini bekler. Uygulama o test
sırasında çalışmaz.

## İmaj build (opsiyonel — repo kökünden)
```bash
docker build -f catalog-service/Dockerfile -t kervan/catalog-service:local .
```

## Örnek istek
```bash
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{
    "sku": "PHN-001",
    "name": "Kervan Akıllı Telefon X",
    "brand": "Kervan",
    "categoryPath": "elektronik/telefon",
    "price": { "amount": 14999.90, "currency": "TRY" },
    "attributes": { "ram": "8GB", "renk": "siyah" }
  }'
```

## Sonraki fazlarla bağ
- **Faz 3:** Ürün değişiklikleri Debezium (MongoDB change streams) ile Kafka'ya akacak.
- **Faz 5:** Bu event'ler Elasticsearch'e indekslenip asıl arama oraya taşınacak (CQRS).
