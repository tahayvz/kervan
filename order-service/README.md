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

## Çok kopyalı çalışma ve tıkanmama

Servis birden fazla kopya hâlinde çalıştığında iki sorun doğar; ikisi de çözüldü:

| Sorun | Çözüm |
|---|---|
| İki kopya aynı satırları okuyup olayı iki kez yayınlar | `FOR UPDATE SKIP LOCKED` — her kopya farklı satırları kilitler, birbirini beklemez |
| Kalıcı olarak gönderilemeyen bir kayıt kuyruğun başını tıkar | `attempts` sayacı; sınıra ulaşan kayıt sorgunun dışında kalır, kuyruk akmaya devam eder |

Gönderim `send-timeout` ile sınırlıdır. Zaman aşımsız bekleme, broker erişilemezken
transaction'ı ve veritabanı bağlantısını dakikalarca açık tutar; bu da Kafka kesintisini
sipariş alma yoluna bulaştırırdı — outbox'ın önlemek için var olduğu şeyin ta kendisi.

Kenara alınmış kayıtları bulmak için:

```sql
SELECT id, aggregate_id, attempts, last_error
FROM outbox_messages
WHERE published_at IS NULL AND attempts >= 5
ORDER BY last_attempt_at DESC;
```

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

### İzleme bağlamı satırda taşınır

Olay Kafka'ya siparişi alan iş parçacığından gitmiyor: arada veritabanı ve Debezium
var. Bu yüzden izleme (trace) bağlamı kendiliğinden taşınamaz ve `trace_parent`
sütununda, veriyle birlikte yazılır. Debezium sütunu Kafka'nın `traceparent`
başlığına kopyalar; tüketen servis aynı ize devam eder.

Sütunu **outbox adaptörü** doldurur, iş mantığı değil: `OrderService` izlemeden
habersizdir. Uygulama içi yayıncı da aynı başlığı elle koyar, böylece Debezium ile
arasında fark kalmaz. Gerekçe: [ADR-0013](../docs/adr/0013-trace-context-across-outbox.md)

## Olay biçimi: Avro + Schema Registry

Olay, servisin dışına çıkan bir sözleşmedir. Şemalar bu serviste değil, ortak
[`event-contracts`](../event-contracts/README.md) modülünde durur; olayı tüketen servis
o modüle bağlanır, bu servise değil.

Serileştirme **sipariş yazan transaction'ın içinde** yapılır ve sonuç `outbox_messages`
tablosuna ikili (`bytea`) olarak yazılır:

```
OrderPlaced (domain) ──▶ OrderPlacedAvroMapper ──▶ Avro ──▶ [0x00][şema kimliği][gövde]
                                                       │
                          Schema Registry ◀── kaydet ──┘
```

Şemanın kendisi mesajda taşınmaz, yalnızca kimliği. Okuyan taraf şemayı Registry'den
bir kez çeker.

Serileştirme neden transaction'ın içinde? Şema Registry tarafından reddedilirse
(uyumsuz bir değişiklik yapılmışsa) sipariş de yazılmaz. Kimsenin duymayacağı bir
sipariş oluşturmaktansa isteği reddetmek doğrudur.

Para alanları Avro `decimal` ile taşınır, `double` ile değil: kayan noktada kuruş
yuvarlanır ve fatura yanlış çıkar. Ölçek, veritabanındaki `NUMERIC(19,4)` ile aynı
tutuldu; böylece 3 ondalıklı para birimleri (KWD, BHD, OMR) de kayıpsız sığar.

Karar kaydı: [ADR-0008](../docs/adr/0008-avro-schema-registry.md)

## Olayı Kafka'ya kim taşır?

Outbox, olayı siparişle aynı transaction'da tabloya yazar. Tablodan alıp Kafka'ya
götüren birinin olması gerekir. İki yol var ve ikisi de bu depoda:

| | `OutboxPublisher` (uygulama içi) | Debezium (CDC) |
|---|---|---|
| Nasıl okur | Tabloyu periyodik sorgular | Veritabanının değişiklik günlüğünü (WAL) okur |
| Veritabanı yükü | Her turda sorgu, boşta bile | Yok |
| Gecikme | Tur aralığı kadar | Neredeyse anlık |
| Uygulama kodu | Kafka istemcisi taşır | Kafka'ya hiç dokunmaz |
| İşletim | Ek bileşen yok | Kafka Connect ayakta tutulmalı |

Hangisinin çalışacağı `kervan.outbox.publisher.enabled` ile seçilir. Debezium
devredeyken uygulama içi yayıncı **kapatılmalıdır**; ikisi birden açıksa aynı olay
iki kez gider. Ayarın bean'i gerçekten kaldırdığı testle sabitlendi
(`OutboxPublisherRegistrationTest`).

Konektör ayarları ve işletim notları: [`infra/docker/debezium/README.md`](../infra/docker/debezium/README.md)

### Tablo neden büyümüyor?

Outbox bir kuyruktur ama tablo kendini boşaltmaz. `OutboxCleaner` saatte bir çalışır ve
saklama penceresinden (varsayılan 7 gün) eski kayıtları siler.

Ölçüt, kayıtları kimin taşıdığına göre değişir. Uygulama içi yayıncı devredeyken
yalnızca `published_at` damgalı kayıtlar silinir; damgasız eski bir kayıt
gönderilememiş demektir ve silinmesi olayı kaybetmek olurdu. Debezium devredeyken ise
damga hiç konmaz — tek ölçüt yaştır.

Bunun bedeli açık: Debezium saklama penceresinden uzun süre durursa henüz okumadığı
satırlar silinir. Pencere bu yüzden geniş tutuldu ve Debezium'un durup durmadığı
replication slot gecikmesinden izlenir.

Silme parti parti yapılır ve **her parti kendi transaction'ında** çalışır. Sınırsız tek
bir `DELETE`, tablo büyümüşse milyonlarca satırı tek transaction'da siler ve sipariş
yazan istekler o süre boyunca bekler.

Bir tur, silinecek kayıt kalmayana kadar sürer — turda tek parti silinseydi temizlik
hızı parti boyutu ÷ tur aralığında sabitlenirdi ve sipariş hızı bunu geçtiği anda tablo
büyümeye devam ederdi. Hiçbir şey de uyarmazdı; log "sildim" derdi. Turun bir üst sınırı
var; sınıra takılmak "temizlik yetişemiyor" demektir ve uyarı olarak loglanır.

## Saga: siparişin üç servise yayılan akışı

```
sipariş alındı ──▶ ReserveStock
                     │
       StockReserved ┤                    StockReservationFailed
                     ▼                              │
               ProcessPayment                       ▼
                     │                        sipariş İPTAL
   PaymentProcessed  ┤  PaymentFailed
           ▼         │        ▼
     sipariş ONAY    │   ReleaseStock ──▶ StockReleased ──▶ sipariş İPTAL
```

Akışı bu servis yürütür (orchestration). Alternatifi choreography'dir: her servis bir
sonrakini tetikler. Orada "sipariş neden iptal oldu" sorusunun cevabı beş servise
dağılır; telafi eden bir akışta bu, hata ayıklamayı imkânsıza yakın kılar (ADR-0005).

**Saga siparişle aynı transaction'da başlar.** İlk komut, siparişin kendisi ve
`OrderPlaced` olayıyla birlikte outbox'a yazılır. Ayrı bir adımda gönderilseydi araya
giren bir çökme siparişi oluşturur ama saga'yı hiç başlatmazdı: müşteri sipariş
verdiğini görür, arkada hiçbir şey olmaz.

**Ödeme başarısızsa sipariş hemen iptal edilmez.** Önce stok geri bırakılır, iptal
telafi tamamlanınca gelir. Aksi hâlde müşteriye "iptal edildi" derken stok hâlâ
tutuluyor olurdu.

**Tekrar gelen olaylar.** Teslimat en az bir kezdir. Her adım saga'yı kilitleyerek
okur ve geçişin izinli olup olmadığına `SagaState` karar verir. İzinsiz bir geçiş,
olayın zaten işlendiği anlamına gelir — hata değil, yok sayılır. Kilit olmasaydı aynı
siparişin iki olayı yan yana işlenip aynı komut iki kez gönderilebilirdi.

**Durum neden tabloda?** Saga uzun ömürlüdür; adımlar arasında dakikalar geçebilir ve
servis bu sırada yeniden başlayabilir. Bellekte tutulsaydı her yeniden başlatma devam
eden bütün siparişleri unuturdu.

Sıkışıp kalmış saga'ları bulmak için:

```sql
SELECT * FROM order_sagas
WHERE state NOT IN ('COMPLETED', 'CANCELLED')
  AND updated_at < now() - interval '15 minutes';
```

## Mesajlar nereye gidiyor?

Bu servis hem kendi **olaylarını** hem saga'nın **komutlarını** outbox'a yazar. Hepsi
tek konuya gitseydi komutlar yanlış yere düşerdi; bu yüzden hedef konu satırın
kendisinde (`destination` sütunu) durur ve Debezium yönlendirmeyi ona göre yapar.

| Mesaj | Hedef |
|---|---|
| `OrderPlaced`, `OrderConfirmed`, `OrderCancelled` | `kervan.orders.events` |
| `ReserveStock`, `ReleaseStock` | `kervan.inventory.commands` |
| `ProcessPayment`, `RefundPayment` | `kervan.payments.commands` |

Hedefi `aggregate_type` sütununa yüklemek de mümkündü (Debezium'un belgelenmiş yolu
budur) ama o sütun "hangi toplam" sorusunun cevabı; üstüne ikinci bir anlam yüklemek
onu bulanıklaştırırdı.

## Katmanlar

```
domain/          Order, OrderLine, Money, OrderStatus, OutboxMessage + portlar
application/     OrderService (use-case'ler), komutlar
infrastructure/  JPA adaptörleri, outbox yayıncısı, Avro yayıncı, saga durumu, Flyway şeması
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
docker compose -f ../infra/docker/docker-compose.yml up -d postgres kafka schema-registry
```

```bash
mvn -pl order-service spring-boot:run
```

Servis `http://localhost:8082`, OpenAPI arayüzü `/swagger-ui.html`.

## Testler

```bash
mvn -pl order-service test
```

107 test: domain birim testleri (para aritmetiği, durum makinesinin tüm geçiş matrisi,
sipariş toplamı), use-case testleri (mock port'larla), yayıncı testleri (anahtarlama,
başarısız gönderimde işaretlememe, deneme sayacı, turun durması), Avro serileştirici
testleri (kablo biçimi, şemanın hangi ad altında kaydedildiği, ölçeği bozuk tutarın
reddi) ve gerçek PostgreSQL + Kafka container'larına karşı çalışan uçtan uca akış testi
— sipariş outbox üzerinden Kafka'ya ulaşır ve tüketici onu Avro şemasıyla çözer.

Ayrıca bir **CDC testi** var: PostgreSQL + Kafka + Kafka Connect container'larını
ayağa kaldırır, depodaki gerçek konektör ayar dosyasını yükler ve yalnızca outbox
tablosuna satır yazarak olayın konuya düşmesini bekler. Uygulama o test sırasında hiç
çalışmaz — Debezium devredeyken uygulamanın Kafka'ya dokunmaması gerektiği için.

Entegrasyon testleri Testcontainers kullanır; Docker çalışıyor olmalıdır.

## Şema

Şema Flyway ile yönetilir (`db/migration/`); Hibernate `ddl-auto: validate` ile
yalnızca doğrular, tabloya dokunmaz.

`V4`, temizliğin kullandığı `occurred_at` indeksini ekler. Mevcut kısmi indeks
yalnızca `published_at IS NULL` satırları kapsadığı için temizlik sorgusu onu
kullanamaz ve tabloyu baştan sona tarardı.

`V3`, payload sütununu `TEXT`'ten `BYTEA`'ya çevirir. Migration, tabloda yayınlanmamış
kayıt varsa **bilerek hata verir**: eski kayıtlar JSON'dur, baytlara çevrilseler bile
Avro olarak okunamazlar. Doğru sıra, eski sürüm outbox'ı boşaltana kadar beklemek ve
sonra dağıtmaktır. Sessizce bozuk veri üretmektense dağıtımı durdurmak tercih edildi.

`outbox_messages` üzerindeki kısmi indeks yalnızca `published_at IS NULL` satırları
kapsar: yayınlanmış milyonlarca kayıt indekste yer kaplamaz, yayıncının sorgusu sabit
maliyetli kalır.
