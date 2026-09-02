# Order Service

Sipariş oluşturma ve sorgulama. Siparişin kaydı ile `OrderPlaced` olayı **aynı
veritabanı transaction'ında** yazılır; olayı Kafka'ya taşımak ayrı bir işin sorumluluğudur.

## Neden Transactional Outbox?

Siparişi kaydetmek ve olayı yayınlamak iki ayrı sistemdir ve ortak transaction'ları
yoktur. Doğrudan yayınlama denendiğinde iki hata mümkündür:

| Sıra | Sonuç |
|---|---|
| DB commit edildi → Kafka'ya yazmadan önce çöktü | Sipariş var, olay yok. Stok düşülmez, bildirim gitmez. |
| Kafka'ya yazıldı → DB rollback oldu | Olay var, sipariş yok. Downstream var olmayan siparişi işler. |

Buna *dual-write problemi* denir; retry ile çözülmez çünkü hangi tarafın doğru olduğu
bilinemez.

Outbox, olayı siparişle **aynı transaction içinde aynı veritabanına** yazar. Tek commit
olduğu için ikisi ya birlikte var olur ya birlikte yok olur. `OutboxPublisher` yazılmış
kayıtları periyodik okuyup Kafka'ya taşır ve başarılı olanları işaretler.

Bedeli: teslimat **en az bir kez** (at-least-once). Yayınlandıktan sonra işaretlemeden
önce çökme olursa aynı olay tekrar gider — bu yüzden tüketiciler idempotent olmalıdır;
outbox kaydının `id`'si tekrarları elemek için sabit anahtardır.

Karar kaydı: [ADR-0004](../docs/adr/0004-transactional-outbox-debezium.md)

## Akış

```
POST /api/v1/orders
        │
        ▼
   OrderService.placeOrder()          ┌── tek transaction ──┐
        ├─ orders tablosuna yaz       │                     │
        └─ outbox_messages'a yaz      └─────────────────────┘
        │
        ▼  (arka planda, periyodik)
   OutboxPublisher
        ├─ published_at IS NULL kayıtları oku
        ├─ Kafka'ya gönder (key = orderId)
        └─ published_at damgala
```

Kafka'ya `aggregateId` anahtarıyla yazılır: aynı siparişin olayları aynı partition'a
düşer, o sipariş için sıra korunur.

## Katmanlar

```
domain/          Order, OrderLine, Money, OrderStatus, OutboxMessage + portlar
application/     OrderService (use-case'ler), komutlar
infrastructure/  JPA adaptörleri, outbox yayıncısı, Flyway şeması
web/             REST controller, DTO'lar, RFC 7807 hata yönetimi
```

`domain` paketinde Spring ya da JPA anotasyonu yoktur; iş kuralları veritabanı
olmadan test edilir.

## API

| Metot | Yol | Açıklama |
|---|---|---|
| `POST` | `/api/v1/orders` | Sipariş oluşturur (201) |
| `GET` | `/api/v1/orders/{id}` | Siparişi getirir (200 / 404) |

Hatalar RFC 7807 `application/problem+json` biçimindedir.

```bash
curl -X POST http://localhost:8082/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerId": "c-1",
        "currency": "TRY",
        "lines": [
          { "productId": "p-1", "sku": "SKU-1", "quantity": 2, "unitPrice": 100.00 }
        ]
      }'
```

## Çalıştırma

```bash
docker compose -f ../infra/docker/docker-compose.yml up -d postgres kafka
```

```bash
mvn -pl order-service spring-boot:run
```

Servis `http://localhost:8082`, OpenAPI arayüzü `/swagger-ui.html`.

## Testler

```bash
mvn -pl order-service test
```

58 test: domain birim testleri (para aritmetiği, durum makinesinin tüm geçiş matrisi,
sipariş toplamı), use-case testleri (mock port'larla), ve gerçek PostgreSQL + Kafka
container'larına karşı çalışan uçtan uca akış testi — siparişin outbox üzerinden
Kafka'ya ulaştığını ve yayınlandı olarak işaretlendiğini doğrular.

Entegrasyon testleri Testcontainers kullanır; Docker çalışıyor olmalıdır.

## Şema

Şema Flyway ile yönetilir (`db/migration/V1__order_schema.sql`); Hibernate
`ddl-auto: validate` ile yalnızca doğrular, tabloya dokunmaz.

`outbox_messages` üzerindeki kısmi indeks yalnızca `published_at IS NULL` satırları
kapsar: yayınlanmış milyonlarca kayıt indekste yer kaplamaz, yayıncının sorgusu sabit
maliyetli kalır.
