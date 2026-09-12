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
docker compose -f infra/docker/docker-compose.yml up -d \
  postgres mongodb mongo-init kafka schema-registry connect
```

`mongo-init` atlanamaz: Mongo replica set modunda ama başlatılmamış durumda kalırsa
**her yazma reddedilir** ve hata mesajı sebebi söylemez. Bir kez çalışır ve çıkar.

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

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @infra/docker/debezium/inventory-outbox-connector.json \
     http://localhost:8083/connectors
```

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @infra/docker/debezium/payment-outbox-connector.json \
     http://localhost:8083/connectors
```

Durumlarını gör:

```bash
curl -s http://localhost:8083/connectors/kervan-order-outbox/status | jq
```

```bash
curl -s http://localhost:8083/connectors/kervan-catalog-products/status | jq
```

## Dört konektör, iki farklı iş

| | outbox konektörleri (3) | `kervan-catalog-products` |
|---|---|---|
| Kaynak | `orders`, `inventory`, `payment` DB'lerindeki outbox tabloları | `catalog` DB, `products` koleksiyonu |
| Ne okur | WAL | change streams (oplog) |
| Taşıdığı şey | **domain olayı** | Belgenin **kendisi** |
| Sözleşme | `event-contracts` (Avro) | Debezium change event (JSON) |
| Konu | `kervan.<servis>.events` | `kervan.catalog.products` |

Üç outbox konektörü aynı desendir; yalnızca veritabanı, slot adı ve hedef konu farklı.
Dördüncüsü farklı bir iş yapar:

**Bu fark bilinçli.** Outbox, "şu iş oldu" diyen bir olay yayınlamak içindir; olayın
biçimi servislerin üzerinde anlaştığı bir sözleşmedir ve tek yerde yazılıdır.

Katalog akışı ise verinin kendisinin kopyasıdır. Amacı bir iş olayını duyurmak değil,
katalog verisini başka bir yere yansıtmaktır — Faz 5'teki arama indeksi bunun ilk
müşterisi olacak.

İkisini aynı şey saymak, iç veri modelini dış sözleşme hâline getirmek olurdu:
`products` koleksiyonundaki her alan adı değişikliği, onu dinleyen herkesi kırardı.
Katalog akışını tüketen taraf bunu bilerek tüketir; sipariş olaylarını tüketen taraf
ise sözleşmeye güvenir.

### Her slot adı ayrı olmalı

Üç outbox konektörü ayrı veritabanlarını okur ama aynı PostgreSQL sunucusunda. Her
birinin **kendi replication slot'u ve kendi publication'ı** var
(`kervan_<servis>_outbox`). Aynı adı paylaşsalardı biri diğerinin okuduğu konumu
ilerletir ve arada kalan değişiklikler kaybolurdu.

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

**Outbox konektörlerinde `heartbeat.interval.ms` YOK — bilerek.**
Kalp atışı kaydının değeri bir `STRUCT`'tır; outbox konektörlerinin değer
dönüştürücüsü ise `ByteArrayConverter` (payload ham Avro baytı olduğu için zorunlu).
İkisi bağdaşmaz:

```
DataException: Invalid schema type for ByteArrayConverter: STRUCT
```

En sinsi tarafı: kalp atışı yalnızca **akış sessizken** üretilir. Mesaj akarken hiç
oluşmaz — yani kısa entegrasyon testleri yeşil kalır, üretimde ilk sessiz anda görev
ölür. Konektör `RUNNING` görünür, görevi `FAILED`.

Bedeli: outbox sessizken ama veritabanının geri kalanı meşgulken WAL birikir.
Telafisi replication slot gecikmesini **izlemek** (aşağıdaki işletim tuzağı).
Katalog konektöründe kalp atışı **duruyor**: oranın değer dönüştürücüsü JSON.

**`heartbeat.interval.ms` (katalog konektöründe) = `10000`.** Her iki konektörde de var, aynı sınıf sorunu
çözüyor: kaynak sessizken konektörün kaydettiği konum ilerlemez.

- Postgres'te sonuç WAL birikmesidir: outbox sessizken ama veritabanının geri kalanı
  meşgulken slot'un onaylanan konumu yerinde sayar. Veritabanı **tümüyle** sessizse
  heartbeat de yetmez; o durumda `heartbeat.action.query` ile küçük bir yazma
  yaptırmak gerekir.
- MongoDB'de sonuç daha sert: koleksiyon sessizken kaydedilen resume token eskir.
  Oplog dönüp token'ın gösterdiği nokta düşerse konektör kaldığı yerden devam
  **edemez**. `snapshot.mode=no_data` olduğu için yeniden başlarken aradaki
  değişiklikleri de getiremez — o değişiklikler kaybolur.

**`table.fields.additional.placement` = `trace_parent:header:traceparent`.**
İzleme bağlamını satırdan Kafka başlığına kopyalar. Biçim
`<sütun>:<yerleşim>:<başlık adı>`. Gerekçe ADR-0013'te: Debezium'un ne isteği ne de
iş parçacığı vardır, bu yüzden bağlamı kendisi üretemez; satırda yazanı taşır.

**`header.converter` = `StringConverter`.** Bu satır olmadan yukarıdaki ayar
işe yaramaz. Connect'in varsayılan başlık dönüştürücüsü JSON'dur ve değeri
**tırnak içinde** yazar: `"00-4bf9...-01"`. W3C ayrıştırıcısı tırnaklı metni
geçersiz sayıp atar — ne istisna olur ne log, yalnızca izleme zinciri kopar.
`OutboxCdcIntegrationTest` başlığı "var mı" diye değil, değeri **birebir eşit mi**
diye kontrol eder; "içeriyor" yazsaydı tırnaklı hâli de testi geçerdi.

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

## Outbox temizliği

Outbox bir kuyruktur ama tablo kendini boşaltmaz. Temizliği `OutboxCleaner` yapar;
saatte bir çalışır ve saklama penceresinden eski kayıtları siler.

Ölçüt, kayıtları kimin taşıdığına göre değişir:

| Taşıyan | Silinen |
|---|---|
| Uygulama içi yayıncı | Yalnızca `published_at` damgalı eski kayıtlar |
| Debezium | Eski kayıtların hepsi |

Sebep: Debezium hiçbir şey damgalamaz — satırı WAL'dan okur, tabloya dokunmaz. Orada
"yayınlandı mı" diye bakılacak bir işaret yoktur, tek ölçüt yaştır. Yayıncı modunda ise
damgasız eski bir kayıt *gönderilememiş* demektir; silinmesi olayın kaybolması olurdu.

Hangi modda olunduğunu ayrı bir ayar değil, zaten var olan
`kervan.outbox.publisher.enabled` söyler. İki ayrı anahtar olsaydı biri değişip
diğeri unutulabilirdi.

```yaml
kervan:
  outbox:
    cleanup:
      enabled: true
      retention: 7d          # bu yaştan eskiler silinir
      interval-ms: 3600000   # saatte bir
      batch-size: 1000       # tek partide azami satır
      max-batches-per-run: 100   # turda azami parti (100 x 1000 = 100.000 satır)
```

**Saklama penceresi neden geniş?** Debezium modunda ölçüt yaş olduğu için, Debezium
pencereden daha uzun süre durursa henüz okumadığı satırlar silinir ve o olaylar
kaybolur. Bu yüzden pencere Debezium'un durabileceği en uzun süreden uzun tutulmalı ve
durup durmadığı ayrıca izlenmelidir — replication slot'un gecikmesi bunu söyler
(yukarıdaki sorgu).

**Neden parti parti?** Sınırsız tek bir `DELETE`, tablo büyümüşse milyonlarca satırı
tek transaction'da siler; tablo o süre boyunca kilitli kalır ve sipariş yazan istekler
bekler. Her parti kendi transaction'ında çalışır.

**Neden turda tek parti değil?** Öyle olsaydı temizlik hızı `batch-size ÷ interval`de
sabitlenirdi — yukarıdaki değerlerle saatte 1000 satır. Sipariş hızı bunu geçtiği anda
tablo büyümeye devam eder ve hiçbir şey uyarmaz; log "sildim" der. Bu yüzden bir tur,
silinecek kayıt kalmayana kadar sürer. `max-batches-per-run` üst sınırdır ve ona
takılmak "temizlik yetişemiyor" demektir — uyarı olarak loglanır.
