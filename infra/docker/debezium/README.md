# Debezium — outbox tablosundan Kafka'ya (CDC)

Bu klasör, `order-service`'in `outbox_messages` tablosunu okuyup olayları Kafka'ya
taşıyan Debezium konektörünün ayarlarını içerir.

Karar kaydı: [ADR-0004](../../../docs/adr/0004-transactional-outbox-debezium.md)

## Neden

Outbox deseni dual-write problemini çözer: sipariş ve olay aynı transaction'da
yazılır. Ama olayı tablodan alıp Kafka'ya götüren birinin olması gerekir.

İki seçenek var:

| | Uygulama içi yayıncı (`OutboxPublisher`) | Debezium (CDC) |
|---|---|---|
| Nasıl okur | Tabloyu periyodik sorgular | Veritabanının değişiklik günlüğünü (WAL) okur |
| Veritabanı yükü | Her turda sorgu, boşta bile | Yok; günlüğü takip eder |
| Gecikme | Tur aralığı kadar | Neredeyse anlık |
| Uygulama kodu | Kafka istemcisi taşır | Kafka'ya hiç dokunmaz |
| İşletim | Ek bileşen yok | Kafka Connect ayakta tutulmalı |

Bu projede ikisi de var. Hangisinin çalışacağı `kervan.outbox.publisher.enabled`
ayarıyla seçilir. Debezium devredeyken uygulama içi yayıncı **kapatılmalıdır**;
ikisi birden açıksa aynı olay iki kez gider.

## Kurulum

Postgres'in `wal_level=logical` ile çalışması gerekir. Compose dosyasında ayarlı.
Varsayılan `replica` seviyesinde günlük satırın içeriğini taşımaz, yalnızca fiziksel
blok değişikliğini; Debezium bu günlükten satırı çözemez.

```bash
docker compose -f infra/docker/docker-compose.yml up -d postgres kafka schema-registry connect
```

Konektörü kaydet:

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @infra/docker/debezium/order-outbox-connector.json \
     http://localhost:8083/connectors
```

Durumunu gör:

```bash
curl -s http://localhost:8083/connectors/kervan-order-outbox/status | jq
```

## Ayarların anlamı

**`transforms.outbox` — EventRouter.** Debezium ham hâlde "şu tabloya şu satır
eklendi" biçiminde bir değişiklik kaydı üretir. Bu SMT (single message transform)
onu bir olaya çevirir: `payload` sütunu mesajın gövdesi, `aggregate_id` anahtarı,
`id` ise tekrarları elemeye yarayan başlık olur.

**`value.converter` = `ByteArrayConverter`.** Gövde tabloda zaten Avro olarak
duruyor ve şemanın kimliği baytların ilk beşinde. Connect'in gövdeyi çözmesine ya da
yeniden biçimlendirmesine gerek yok; olduğu gibi taşır. Payload JSON olarak
saklansaydı Connect'in şemayı **çıkarım yoluyla** üretmesi gerekirdi ve bu, bizim
`event-contracts` modülünde yazılı olan sözleşme olmazdı (ADR-0008).

**`route.topic.replacement` sabit.** Normalde konu adı `aggregate_type` alanından
türetilir. Burada sabit tutuldu ki Debezium ile uygulama içi yayıncı **aynı konuya**
yazsın; birinden diğerine geçmek tüketicileri etkilemesin.

**`table.field.event.timestamp` kullanılmadı.** Olayın iş zamanı (`placedAt`) zaten
Avro gövdesinin içinde. Aynı bilgiyi bir de Kafka kaydının zaman damgasına taşımak,
`TIMESTAMPTZ` sütununu Connect'in beklediği türe çevirmeyi gerektirir ve kırılgandır.
Kayıt zaman damgası olarak Debezium'un kendi olay zamanı bırakıldı; o da değişikliğin
gerçekte ne zaman olduğunu söyler.

**`binary.handling.mode` = `bytes`.** Varsayılanı `bytes`tir ama açıkça yazıldı:
`base64` olsaydı gövde bir kez daha kodlanır ve tüketici Avro yerine metin görürdü.

## Apple Silicon notu

Debezium'un ARM imajındaki Java çalışma zamanı bazı Mac'lerde ilk yerel çağrıda
çöküyor:

```
SIGILL ... java.lang.System.registerNatives ... linux-aarch64
```

Bu depodaki koddan ya da buradaki ayarlardan bağımsızdır; imaj yalnızca
`java -version` çalıştırırken bile aynı şekilde çöker:

```bash
docker run --rm --entrypoint java quay.io/debezium/connect:3.0.0.Final -version
```

Çözüm: imajın **amd64** sürümü çalıştırılır. Linux'ta bu zaten yerel sürümdür;
Apple Silicon'da emülasyonla çalışır (yavaş ama çalışır).

- Compose'da `platform: linux/amd64` satırı bunu sağlar.
- `OutboxCdcIntegrationTest` imajı **digest ile** sabitler; etiket makinenin
  mimarisine göre çözülürken digest her yerde aynı amd64 imajını seçer.

Test ayrıca, konteyner yine de kalkmazsa **yerelde** kendini atlar ve sebebini
yazar. CI ortamında atlama yoktur: orada başlatma hatası doğrudan teste yansır,
böylece konektör ayarındaki bir hata görünmeden kalmaz.

## İşletim tuzağı: replication slot

Debezium, Postgres'te bir **replication slot** açar (`kervan_order_outbox`). Slot,
"bu tüketici WAL'ın neresine kadar okudu" bilgisini tutar ve Postgres, slot'un
okumadığı WAL'ı **silmez**.

Konektörü kaldırıp slot'u bırakırsan Postgres WAL'ı sonsuza kadar biriktirir ve disk
dolar. Konektörü kalıcı olarak kaldırırken slot da düşürülmelidir:

```sql
SELECT pg_drop_replication_slot('kervan_order_outbox');
```

İzlemesi:

```sql
SELECT slot_name, active, pg_size_pretty(
         pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS geride_kalan
FROM pg_replication_slots;
```

`geride_kalan` sürekli büyüyorsa konektör durmuş demektir.

## Henüz yapılmamış: outbox temizliği

Uygulama içi yayıncı gönderdiği kaydı `published_at` ile işaretler. Debezium böyle bir
işaret bırakmaz — satırı WAL'dan okur, tabloya dokunmaz. Yani CDC devredeyken
`outbox_messages` sürekli büyür.

Gerekli olan, belli bir yaştan eski kayıtları silen bir bakım işidir. Ölçüt "yayınlandı
mı" olamaz, çünkü o bilgi artık tabloda yok; ölçüt yaş olmalıdır ve seçilen süre,
Debezium'un en uzun durabileceği süreden uzun tutulmalıdır. Henüz yazılmadı.
