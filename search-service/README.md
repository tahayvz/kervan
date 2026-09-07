# search-service

Ürün araması: metin, çok kriterli süzme ve facet sayımları.

Bu servis **hiçbir şey yazmaz.** CQRS'in okuma tarafıdır: veri katalogta üretilir,
burada yalnızca aramaya uygun bir kopyası tutulur.

Karar gerekçesi: [ADR-0010](../docs/adr/0010-search-read-model.md)

## Akış

```
catalog-service ──▶ MongoDB
                       │  (change streams)
                       ▼
                   Debezium
                       │
            kervan.catalog.products
                       │
                       ▼
              search-service ──▶ Elasticsearch
```

Katalog servisi arama diye bir şey olduğunu bilmez. Yeni bir okuma modeli eklemek
(öneri motoru, veri ambarı) ona dokunmayı gerektirmez.

## İndeks türev veridir

Elasticsearch'teki her şey katalogun kopyasıdır. İndeks kaybolursa veri kaybolmaz:
tüketici `earliest`'ten okuyacak şekilde ayarlı, akış baştan oynatılarak indeks
yeniden kurulur. Kurtarma planı budur.

## Alan tipleri neden elle tanımlı

Elasticsearch var olmayan bir indekse yazıldığında onu **kendi tahminiyle** oluşturur
ve her metin alanını `text` yapar. O hâlde marka ve kategori analiz edilir, kelimelere
bölünür ve iki şey birden bozulur:

- **Facet sayımı çalışmaz** — analiz edilmiş alanda toplama yapılamaz, sorgu düşer.
- **Süzme yanlış çalışır** — "New Balance" seçimi "New" içeren her şeyi getirir.

Sınıftaki `@Field` tanımları kendiliğinden uygulanmaz; yalnızca indeksi **biz**
oluşturursak kullanılır. `ProductIndexInitializer` bunu ilk belge yazılmadan önce
yapar.

Var olan indekse dokunulmaz: Elasticsearch mevcut bir alanın tipini değiştirmeye izin
vermez. Eşleme değişikliği yeni indeks açıp veriyi yeniden yazmayı gerektirir — bu
model türev olduğu için o iş, akışı baştan oynatmaktan ibarettir.

## Tekrar gelen olaylar: ayrı düzenek yok

Teslimat en az bir kezdir. İdempotentlik için ayrı bir tablo ya da kontrol yok; iş
zaten kendi içinde çözüyor:

Kayıt, kaynak belgenin **kendi sürümüyle** yazılır (`versionType = EXTERNAL`) ve
Elasticsearch daha küçük bir dış sürümü reddeder. Tekrar gelen olay sonucu
değiştirmez; geç kalmış bir olay yeni veriyi ezemez. Tek mekanizmadan hem
idempotentlik hem sıra koruması gelir.

## Sözleşmesiz kaynak — bilinen bedel

Bu akış bir domain olayı değil, katalog belgesinin **ham kopyasıdır** (ADR-0004).
Yani sürümlü bir sözleşmesi yok: katalogta bir alan adı değişirse burası kırılır.

Bedel bilerek kabul edildi. Kırılmanın tek yerde olması için çeviri tek sınıfta durur:
`DebeziumChangeEventMapper`.

MongoDB tipleri genişletilmiş JSON içinde sarmalanarak gelir
(`{"$oid":...}`, `{"$numberDecimal":...}`, `{"$date":...}`) ve aynı alan sürücü
ayarına göre sarmalanmış **ya da** düz gelebilir. Çevirici iki hâli de kabul eder;
tek hâli varsayıp yanılmanın bedeli alanın sessizce boş kalmasıdır — hata vermez,
arama sonucu eksik çıkar.

## API

| Metot | Yol | Açıklama |
|---|---|---|
| `GET` | `/api/v1/search/products` | Ürün araması |

Parametreler: `q` (metin), `brand` (çoklu), `category` (ön ek), `minPrice`, `maxPrice`,
`page`, `size`.

```bash
curl 'http://localhost:8087/api/v1/search/products?q=kulaklık&brand=Nike&category=elektronik&minPrice=500&maxPrice=2000'
```

Kimlik doğrulaması yok: ürün araması katalog listeleme gibi herkese açıktır ve burada
kişisel veri dönmez. Yazma ucu hiç yok.

Sayfa boyutunun üst sınırı var; istemcinin tek istekte indeksi boşaltması engellenir.

## Facet'ler neden aynı sorguda

"Bu sonuçlar içinde hangi markadan kaç tane var" sayaçları arama sorgusuyla birlikte
hesaplanır. Ayrı sorgu olsaydı hem iki tur olurdu hem de süzgeçle tutarsız kalabilirdi:
kullanıcı "elektronik" seçmişken giyimdeki Nike'ı sayardı.

## Çalıştırma

```bash
docker compose -f ../infra/docker/docker-compose.yml up -d \
  mongodb mongo-init kafka connect elasticsearch
```

Katalog konektörünü kaydet ([ayrıntı](../infra/docker/debezium/README.md)):

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @../infra/docker/debezium/catalog-products-connector.json \
     http://localhost:8083/connectors
```

```bash
mvn -pl search-service spring-boot:run
```

Servis `http://localhost:8087`, OpenAPI arayüzü `/swagger-ui.html`.

## Testler

```bash
mvn -pl search-service test
```

17 test:

| Test | Neyi doğrular |
|---|---|
| `DebeziumChangeEventMapperTest` | Genişletilmiş JSON'un iki hâlinin de okunması, silme olayında kimliğin anahtardan alınması |
| `ProductSearchIntegrationTest` | Gerçek Elasticsearch'e karşı arama, süzgeçler, facet'ler, sürüm koruması |
| `CatalogToSearchIntegrationTest` | **Tam zincir**: Mongo → Debezium → Kafka → indeks |

Sonuncusu neden var: diğer testler değişiklik olayının şeklini elle yazar, yani
varsayımı test eder. Bu akışın sözleşmesi olmadığı için varsayımın doğruluğunu ancak
gerçek Debezium çıktısı gösterir. Alanlar tek tek doğrulanır — yanlış okunsaydı ürün
yine bulunurdu ama fiyatı boş olurdu.

**Elasticsearch imajı digest ile sabitlenmiştir (amd64).** ARM sürümündeki Java
çalışma zamanı bazı makinelerde ilk yerel çağrıda çöküyor; Debezium imajıyla aynı
arıza. Ayrıntı: [debezium/README.md](../infra/docker/debezium/README.md).
