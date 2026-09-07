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

**Uygulama içi yayıncıyı kapat.** Bu adım atlanırsa her olay iki kez gider: biri
Debezium'dan, biri `OutboxPublisher`'dan. Varsayılan açıktır.

```bash
KERVAN_OUTBOX_PUBLISHER_ENABLED=false mvn -pl order-service spring-boot:run
```

Konektörleri kaydet:

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @infra/docker/debezium/order-outbox-connector.json \
     http://localhost:8083/connectors
```

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @infra/docker/debezium/catalog-products-connector.json \
     http://localhost:8083/connectors
```

Durumlarını gör:

```bash
curl -s http://localhost:8083/connectors/kervan-order-outbox/status | jq
```

```bash
curl -s http://localhost:8083/connectors/kervan-catalog-products/status | jq
```

## İki konektör, iki farklı iş

| | `kervan-order-outbox` (PostgreSQL) | `kervan-catalog-products` (MongoDB) |
|---|---|---|
| Kaynak | `outbox_messages` tablosu | `products` koleksiyonu |
| Ne okur | WAL (write-ahead log) | change streams (oplog) |
| Taşıdığı şey | Uygulamanın yazdığı **domain olayı** | Belgenin **kendisi** |
| Sözleşme | `event-contracts` (Avro, sürümlü) | Debezium change event (JSON) |
| Konu | `kervan.orders.events` | `kervan.catalog.products` |

**Bu fark bilinçli.** Outbox, "şu iş oldu" diyen bir olay yayınlamak içindir; olayın
biçimi servislerin üzerinde anlaştığı bir sözleşmedir ve tek yerde yazılıdır.

Katalog akışı ise verinin kendisinin kopyasıdır. Amacı bir iş olayını duyurmak değil,
katalog verisini başka bir yere yansıtmaktır — Faz 5'teki arama indeksi bunun ilk
müşterisi olacak.

İkisini aynı şey saymak, iç veri modelini dış sözleşme hâline getirmek olurdu:
`products` koleksiyonundaki her alan adı değişikliği, onu dinleyen herkesi kırardı.
Katalog akışını tüketen taraf bunu bilerek tüketir; sipariş olaylarını tüketen taraf
ise sözleşmeye güvenir.

## MongoDB tarafı

Change streams **replica set modu ister**: akış Mongo'nun oplog'una dayanır ve oplog
tek düğümlü (standalone) kurulumda tutulmaz. Compose'daki Mongo tek düğümlü bir
replica set olarak çalışır — küme kurmak için değil, yalnızca oplog için.

Kimlik doğrulama açıkken replica set üyeleri birbiriyle de doğrulaşır ve ortak bir
anahtar dosyası ister. Tek düğüm olduğu için anahtarın kimseyle paylaşılması
gerekmiyor; her açılışta konteyner içinde üretiliyor. Depoda gizli bir dosya
tutmuyoruz.

`mongo-init` konteyneri `rs.initiate()` çağrısını bir kez yapar ve çıkar. Üye adresi
`mongodb:27017` olarak veriliyor; Debezium aynı Docker ağında olduğu için bu adı
çözer.

**Ana makineden bağlanan adrese `replicaSet=rs0` eklemeyin.** Sürücü tek adres
verildiğinde ve URI'de `replicaSet` yazmadığında doğrudan bağlanır, üye aramaz —
`catalog-service`'in lokal adresi bu yüzden değişmedi. `replicaSet=rs0` eklenirse
sürücü keşfe geçer ve üyenin ilan ettiği `mongodb:27017` adresine bağlanmaya çalışır;
o ad Docker ağının dışında çözülmez.

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

**`route.topic.replacement` sabit.** Aynı konu adı uygulamanın
`kervan.outbox.topic` ayarında da yazılı. İkisi ayrı sistemde olduğu için ortak bir
sabitte tutulamıyor; biri değişirse diğeri de değişmelidir. Uyuşmazlıkta mesajlar
kırılmaz (şema kimliği mesajın içinde taşınır) ama şema, mesajların düşmediği bir
konu adının altına kaydedilir ve kayıt defteri yanıltıcı hâle gelir.

Normalde konu adı `aggregate_type` alanından türetilir. Burada sabit tutuldu ki Debezium ile uygulama içi yayıncı **aynı konuya**
yazsın; birinden diğerine geçmek tüketicileri etkilemesin.

**`table.field.event.timestamp` kullanılmadı.** Olayın iş zamanı (`placedAt`) zaten
Avro gövdesinin içinde. Aynı bilgiyi bir de Kafka kaydının zaman damgasına taşımak,
`TIMESTAMPTZ` sütununu Connect'in beklediği türe çevirmeyi gerektirir ve kırılgandır.
Kayıt zaman damgası olarak Debezium'un kendi olay zamanı bırakıldı; o da değişikliğin
gerçekte ne zaman olduğunu söyler.

**`snapshot.mode` = `no_data`.** Varsayılan `initial` olsaydı konektör ilk
başladığında tabloyu baştan tarar ve **mevcut tüm satırları** olay olarak yayınlardı.
Outbox'ta yayınlanmış geçmiş kayıtlar durduğu için, polling'den CDC'ye geçen bir
sistemde bu, sipariş geçmişinin tamamının yeniden yayınlanması demekti. `no_data`
konektörü mevcut WAL konumundan başlatır.

**`publication.autocreate.mode` = `filtered`.** Varsayılan `all_tables`, `CREATE
PUBLICATION ... FOR ALL TABLES` çalıştırır: superuser ister ve `orders`,
`order_lines` değişikliklerini de WAL'dan çözer — hepsi `table.include.list` ile
sonradan elenir, yani boşa iş. `filtered` yalnızca izin listesindeki tabloyu yayınlar.

**`heartbeat.interval.ms` = `10000`.** Outbox sessizken ama veritabanının geri kalanı
meşgulken slot'un onaylanan konumu ilerlemez ve WAL birikir. Heartbeat, konektörün
okuduğu konumu düzenli olarak bildirmesini sağlar. Veritabanı **tümüyle** sessizse
bu da yetmez; o durumda `heartbeat.action.query` ile küçük bir yazma yaptırmak gerekir.

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

## Veritabanı kullanıcısının ihtiyacı olan yetkiler

Debezium tabloyu okumaz, replikasyon akışı açar. Bunun için kullanıcıya normal
`SELECT` yetkisi yetmez:

```sql
-- Replikasyon akışı açabilmek için
ALTER ROLE kervan WITH REPLICATION;

-- publication.autocreate.mode=filtered kullanıcının publication yaratmasını ister;
-- tabloların sahibi olması ya da publication'ın önceden açılmış olması gerekir.
CREATE PUBLICATION kervan_order_outbox_pub FOR TABLE public.outbox_messages;
```

Publication'ı önceden açarsan Debezium onu kullanır ve kullanıcının `CREATE`
yetkisine ihtiyaç kalmaz. Üretimde tercih edilen budur: yetkiyi uygulamaya değil,
dağıtım adımına vermek.

Lokal compose'da bu adımlar gerekmez; oradaki kullanıcı zaten superuser.

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
