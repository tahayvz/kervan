# ADR-0009: Saga mesaj topolojisi — komut/olay ayrımı ve konu başına çok tip

- **Durum:** Accepted
- **Tarih:** 2026-09-07
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Faz 4'te sipariş akışı üç servise yayılıyor: sipariş, stok, ödeme. Orchestration
tabanlı Saga (ADR-0005) bir adım başarısız olduğunda önceki adımları telafi eder.

Bu, iki yeni soruyu doğuruyor:

1. Servisler birbirine ne gönderiyor — *duyuru* mu, *istek* mi?
2. Bu mesajlar hangi konulara yazılacak?

İkinci soru göründüğünden önemli. Kafka'da **sıra yalnızca tek bir konu içinde ve
aynı anahtar için** korunur. Bir siparişin adımları farklı konulara dağılırsa,
tüketici `OrderCancelled`'ı `OrderPlaced`'dan önce görebilir — ve bu, telafi eden bir
sistemde tutarsız duruma yol açar.

## Karar

**1. Komut ile olay ayrılır.**

- **Komut** bir niyettir ve tek bir alıcıya yöneliktir: "stok ayır". Gönderen, kimin
  yapacağını bilir. Adları emir kipindedir (`ReserveStock`, `ProcessPayment`).
- **Olay** olan bitenin duyurusudur; kimin dinlediği gönderenin işi değildir. Adları
  geçmiş zamandır (`StockReserved`, `PaymentFailed`).

Ayrım keyfî değil: komutu reddetmek mümkündür, olayı reddetmek mümkün değildir —
olay zaten olmuştur. Aynı kanalda karışırlarsa bu fark kaybolur.

**2. Her servisin bir komut, bir olay konusu olur.**

| Konu | İçerik |
|---|---|
| `kervan.orders.events` | `OrderPlaced`, `OrderConfirmed`, `OrderCancelled` |
| `kervan.inventory.commands` | `ReserveStock`, `ReleaseStock` |
| `kervan.inventory.events` | `StockReserved`, `StockReservationFailed`, `StockReleased` |
| `kervan.payments.commands` | `ProcessPayment`, `RefundPayment` |
| `kervan.payments.events` | `PaymentProcessed`, `PaymentFailed`, `PaymentRefunded` |

Hepsi **sipariş kimliğiyle** anahtarlanır: bir siparişin bütün adımları aynı
partition'a düşer ve sırası korunur.

**3. Subject adlandırma stratejisi `TopicRecordNameStrategy` olur.**

Confluent'ın varsayılanı subject'i `<konu>-value` yapar, yani konu başına **tek**
şema. Yukarıdaki tabloda her konuda birden çok tip var; varsayılan stratejide ikinci
tip, birincinin uyumsuz bir sürümü sayılıp reddedilirdi.

`TopicRecordNameStrategy` subject'i `<konu>-<kayıt tam adı>` yapar: her tipin kendi
sürüm geçmişi ve kendi uyumluluk denetimi olur, konu ortak kalır.

## Değerlendirilen alternatifler

- **Tip başına ayrı konu** — Varsayılan subject stratejisi bozulmadan kalırdı ve her
  şey basit görünürdü. Ama sıra garantisi kaybolurdu: `StockReserved` ile
  `StockReleased` ayrı konularda olsaydı, tüketici stoğun bırakıldığını ayrıldığından
  önce görebilirdi. Telafi eden bir sistemde bu, düzeltilmesi zor bir tutarsızlıktır.
  **Elendi.**

- **Tek büyük konu (bütün servisler)** — Sıra en güçlü hâlde korunurdu ama her servis
  ilgilenmediği her mesajı okumak zorunda kalırdı ve tek bir konu bütün sistemin
  darboğazı olurdu. **Elendi.**

- **Şemada üst düzey union** — Tek subject altında birden çok tip taşımanın diğer
  yolu. Yeni bir tip eklemek union'ı, yani **paylaşılan** şemayı değiştirir; her
  tüketici yeniden derlenmese de şema sürümü ilerler. Tiplerin birbirinden bağımsız
  evrilmesi engellenir. **Elendi.**

- **Konu başına çok tip + `TopicRecordNameStrategy`** — Seçilen. Sıra korunur, tipler
  bağımsız evrilir, her servis yalnızca kendi konularını okur.

## Sonuçlar

- **Olumlu:** Bir siparişin bütün adımlarının sırası korunur. Yeni bir olay tipi
  eklemek mevcut tiplerin şemasına dokunmaz. Komut/olay ayrımı, kimin kime ne
  söylediğini adından okunur kılar.

- **Olumsuz / ödünler:** Registry'de subject sayısı artar (konu × tip). Tüketici bir
  konudan birden çok tip alacağı için gelen mesajın tipine göre dallanmak zorundadır;
  tek tipli bir konuda buna gerek olmazdı. Konu sayısı da servis başına ikiye çıkar.

- **Tüketici tarafında ayar gerekmez:** Şema, mesajın içindeki kimlikten çözülür;
  subject adına yalnızca üretici bakar. Strateji değişikliği tüketicileri etkilemez.

## İlgili kararlar
- ADR-0005 (Saga orchestration) — telafi mantığının kendisi
- ADR-0008 (Avro + Schema Registry) — şema biçimi ve uyumluluk kuralı
