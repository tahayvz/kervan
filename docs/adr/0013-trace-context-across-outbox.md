# ADR-0013: İzleme bağlamı outbox satırında taşınır

- **Durum:** Accepted
- **Tarih:** 2026-09-11
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Senkron çağrılarda izleme bağlamı kendiliğinden taşınır: istemci `traceparent`
başlığını HTTP isteğine ekler, sunucu okur, zincir devam eder. Kütüphane halleder.

Bu projede sipariş akışı senkron değil. Yol şöyle:

```
POST /api/v1/orders
  └─ order-service: siparişi ve olayı AYNI transaction'da yazar
       └─ (veritabanı)
            └─ Debezium: değişiklik günlüğünü okur, Kafka'ya yazar
                 └─ inventory-service: stok ayırır, kendi olayını outbox'a yazar
                      └─ ... ödeme ... saga tamamlanır
```

Zincirin ortasında **hiç uygulama kodu olmayan** bir adım var. Debezium bir
veritabanı günlüğü okuyucusudur; ne HTTP isteği görür, ne çağıranın iş
parçacığını, ne de bir izleme kütüphanesi çalıştırır. Satırı okuduğu an, siparişi
alan istekten saniyeler veya dakikalar sonrasıdır.

Sonuç: hiçbir şey yapılmazsa her servis kendi izini başlatır. Jaeger'da tek bir
sipariş, birbirine bağlanmayan dört ayrı parça olarak görünür. Tam da izlemenin
cevaplaması gereken soru — "bu sipariş nerede takıldı?" — cevapsız kalır.

## Karar (Decision)

İzleme bağlamı **veriyle birlikte** taşınır: outbox tablosuna `trace_parent`
sütunu eklenir, satır yazılırken o anki W3C `traceparent` değeriyle doldurulur.
Debezium'un `EventRouter` dönüşümü bu sütunu Kafka `traceparent` başlığına
kopyalar. Tüketiciler başlığı okuyup aynı ize devam eder.

Bağlam, iş mantığında değil **outbox adaptöründe** yakalanır: olayı yazan kodun
izlemeden haberi olmaz.

## Değerlendirilen alternatifler (Considered options)

- **Sütun + SMT başlığı (seçilen)**
  - Artı: Debezium yolu ile uygulama içi yayıncı yolu **aynı** başlığı üretir.
    Hangisi açık olursa olsun tüketicinin gördüğü şey değişmez.
  - Artı: taşınan şey standart. Zincirin ucundaki servis Java olmak zorunda değil.
  - Eksi: üç serviste şema değişikliği; outbox düzeneğinin mevcut tekrarına bir
    sütun daha ekliyor (bedeli ADR-0004'te yazılı).

- **Bağlamı Avro payload'ının içine koymak**
  - Eksi: izleme, iş sözleşmesinin parçası olurdu. Her olay şeması bir teşhis
    alanı taşırdı ve şema evrimi (ADR-0008) bu alanı da kapsardı.
  - Eksi: tüketicinin izi bağlaması için önce mesajı çözmesi gerekirdi; ağ
    geçidi/köprü gibi gövdeye bakmayan katmanlar bağlamı göremezdi.

- **Hiç taşımamak, servis başına ayrı iz**
  - Eksi: "sipariş nerede takıldı" sorusu cevapsız kalır. Zaten bu yüzden izleme
    kuruluyor.

- **Yayıncının kendi bağlamını kullanmak (kolay yol)**
  - Yayıncı zamanlanmış bir iştir. Onun izi "arka plan görevi Kafka'ya yazdı"
    der; siparişle ilgisi yoktur. Doğru görünen ama yanlış cevap veren yol.

## Sonuçlar (Consequences)

- **Olumlu:** Bir sipariş Jaeger'da tek zincir: HTTP isteği → stok → ödeme →
  tamamlanma.
- **Olumlu:** İzleme kapalıyken sütun `NULL` kalır ve akış aynen çalışır. İzleme
  bir teşhis aracıdır, iş akışının ön koşulu değil.
- **Olumsuz / ödün:** Outbox satırı başına ~55 bayt.
- **Olumsuz / ödün:** Outbox üreticisinde Spring'in otomatik gözlemi kapalı
  tutulmak zorunda. Açık olsaydı kütüphane başlığı kendi (yanlış) bağlamıyla
  ezerdi. Bu, kodda ve testte açıkça yazılı.
- **Olumsuz / ödün:** Katalog → arama akışı bu kapsamın **dışında**. O akış
  outbox okumaz; Debezium MongoDB koleksiyonunun kendisini okur (ADR-0004,
  ARCHITECTURE §4.2). Taşınacak bir bağlam sütunu yok ve eklemek, izleme alanını
  katalog dokümanının içine sokmak olurdu. Sonuç: bir ürün güncellemesinin arama
  indeksine yansıması ayrı bir iz olarak görünür. Kabul edildi — orada sorulan
  soru "bu istek nerede takıldı" değil, "indeks ne kadar geride" (gecikme
  metriği, 6b).
- **Takip / risk:** Konektörde `header.converter` **düz metin** olmalı. Connect'in
  varsayılanı JSON'dur ve değeri tırnak içinde yazar; W3C ayrıştırıcısı böyle bir
  başlığı geçersiz sayıp sessizce atar — ne hata olur ne log, yalnızca zincir
  kopar. `OutboxCdcIntegrationTest` bu değeri eşitlik ile kontrol eder.

## İlgili

- [ADR-0004](0004-transactional-outbox-debezium.md) — outbox ve CDC
- [ADR-0012](0012-observability-instrumentation.md) — enstrümantasyon seçimi
