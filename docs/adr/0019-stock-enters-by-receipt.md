# ADR-0019: Stok makbuzla girer, mutlak atamayla değil

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

`inventory-service`'in **hiç HTTP ucu yoktu**. Stok yalnızca Kafka komutlarıyla
ayrılıyor ve geri bırakılıyordu; girişi yoktu. Dahası `StockRepository.saveAll`
sadece güncelliyor — olmayan bir satır için `IllegalStateException` atıyor. Yani
yeni bir SKU'nun stok kaydını bu servis hiçbir şekilde açamıyordu.

Sonucu tohumlama betiğinde görünüyordu:

```bash
docker exec -i kervan-postgres psql -U kervan -d inventory ...
INSERT INTO stock_items (sku, available_quantity, ...)
```

Betik, `scripts/seed.sh`'in kendi başındaki yorumda savunduğu ilkeyi deliyordu:
*"Ağ geçidi üzerinden, çünkü amaç yalnızca veri üretmek değil; yolun kendisini de
çalıştırmak."* Stok için tam tersi yapılıyordu — başka bir servisin veritabanına
dışarıdan yazmak, ADR-0001'in servis sınırı tanımını ihlal eder.

## Karar (Decision)

Stok sisteme **mal kabul makbuzuyla** girer:

```
POST /api/v1/stock/{sku}/receipts   { "receiptId": "...", "quantity": 500 }
```

Miktar mevcut stoğa **eklenir**. `receiptId` istemcinin verdiği bir kimliktir ve
makbuz tablosunun birincil anahtarıdır; aynı makbuz iki kez gelirse miktar bir kez
eklenir.

Üç destekleyici karar:

1. **Yetki: yalnızca `ADMIN`** — okuma da dâhil. Kalan stok ticari bilgidir.
   Servise bu yüzden ilk kez güvenlik yapılandırması eklendi (resource server,
   ADR-0007 deseni).

2. **İdempotentlik ayrı bir "işlenmiş istekler" tablosuyla değil**, işin kendi
   doğal anahtarıyla sağlanır. Aynı gerekçe `reservations.order_id UNIQUE` için
   zaten yazılıydı (ADR-0005 civarı): ikinci bir tablo, senkron kalması ve
   temizlenmesi gereken ikinci bir şey olurdu.

3. **Karar veritabanına bırakılır:** `INSERT ... ON CONFLICT DO NOTHING`. "Önce
   sorgula, sonra yaz" ikilisinin arasına başka bir istek girebilir ve ikisi de
   "yeni makbuz" sonucuna varabilirdi.

## Değerlendirilen alternatifler (Considered options)

- **Mutlak atama: `PUT /api/v1/stock/{sku}` → "stok artık 500"**
  - Artı: en basit yol. Kendiliğinden idempotent, makbuz tablosu gerekmez.
  - Eksi: **kayıp güncelleme.** Aynı anda gelen iki giriş birbirini sessizce ezer;
    ikisi de mevcut değeri okur, ikisi de kendi sonucunu yazar, biri buharlaşır.
  - Eksi: "mal geldi" ile "sayım düzeltmesi" aynı işleme indirgenir. Oysa biri bir
    *olay*, diğeri bir *iddia*; ikisi farklı yetki ve farklı denetim ister.
  - Eksi: stoğun neden değiştiğinin kaydı hiç tutulmaz.

- **Sunucunun ürettiği makbuz kimliği**
  - Eksi: idempotentliği yok eder. İstemci zaman aşımı alıp yeniden denediğinde
    sunucu yeni bir kimlik üretir, yeni bir makbuz sayar ve miktar iki kez eklenir.
    Tekrarı durdurabilecek tek taraf, isteği tekrarlayan taraftır.

- **Kafka komutu (HTTP yerine)**
  - Artı: servisin mevcut giriş kanalıyla aynı olurdu.
  - Eksi: mal kabulü bir *saga adımı* değil, bir *yönetim işlemi*. Cevabı hemen
    gerekir ("stok ne oldu"), telafisi yoktur ve tetikleyicisi bir insandır.
    Asenkron bir kanala koymak, çağıranı cevap için ayrıca yoklamaya zorlardı.

- **Düzeltme/sayım ucunu da şimdi eklemek**
  - Ertelendi. "Kim, hangi gerekçeyle stok düzeltebilir ve bu nasıl denetlenir"
    ayrı bir sorudur; mal kabulüyle karıştırmak ikisini de bulandırırdı.

## Sonuçlar (Consequences)

- **Olumlu:** Stok artık servisin kendi ucundan girer. `seed.sh` başka bir servisin
  veritabanına yazmıyor; tohumlama da kimlik doğrulama, yetki ve doğrulama yolunun
  tamamından geçiyor — yani artık bir şey kanıtlıyor.
- **Olumlu:** Stoğun neden değiştiğinin kaydı var. Mutlak atamada geriye yalnızca
  son sayı kalırdı.
- **Olumlu:** Eşzamanlı iki giriş de sayılır.
- **Olumsuz / ödün:** İstemci bir `receiptId` üretmek zorunda. Bu gerçek bir yük ama
  idempotentliğin bedeli budur ve tekrarı ancak istemci durdurabilir.
- **Olumsuz / ödün:** `inventory-service` artık güvenlik bağımlılığı taşıyor ve iş
  portu boş değil. Servis "yalnızca Kafka dinleyen" sadeliğini kaybetti.
- **Takip / risk:** Stok **düzeltmesi** hâlâ yok. Hasarlı mal, sayım farkı ve yanlış
  girilmiş bir makbuz şu an düzeltilemiyor — bilinen ve kabul edilmiş eksik.
- **Takip / risk:** "Stok geldi" olayı **yayınlanmıyor**. Outbox düzeneği serviste
  zaten var ama tüketicisi yok; kimsenin dinlemediği bir olay, sürümlenmesi gereken
  bir sözleşme yaratır ve karşılığında hiçbir şey vermez. İlk tüketici çıktığında
  eklenir.
