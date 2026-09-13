# ADR-0021: Stok düzeltmesi gerekçeli ve sahiplidir

- **Durum:** Accepted
- **Tarih:** 2026-09-13
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

ADR-0019 stoğun **girişini** açtı: mal kabul makbuzu. Ama gerçek bir depoda stok
başka sebeplerle de değişir ve bunların hiçbiri "mal geldi" değildir:

- Sayım 100 diyor, rafta 95 var
- 5 tanesi kırıldı
- Raf ömrü doldu
- Tedarikçiye iade edildi

ADR-0019'da bu bilerek ertelenmişti; gerekçe şuydu: *"kim, hangi gerekçeyle stok
düzeltebilir ve bu nasıl denetlenir ayrı bir sorudur."* Bu ADR o soruyu cevaplıyor.

Asıl mesele teknik değil. **Düzeltme yetkisi, sistemdeki sayıyı gerçeğe uydurma
yetkisidir** — yani doğru kullanılmazsa eksiği gizleme yetkisi. Stok parayla ölçülür.
Böyle bir yetki, izi tutulmadan verilemez.

## Karar (Decision)

```
POST /api/v1/stock/{sku}/adjustments   { adjustmentId, delta, reason, note? }
GET  /api/v1/stock/{sku}/adjustments
```

Dört kural:

1. **Gerekçe zorunlu ve sınırlı bir listeden.** `COUNT_CORRECTION`, `DAMAGED`,
   `EXPIRED`, `SHRINKAGE`, `RETURNED_TO_SUPPLIER`, `OTHER`. `OTHER` seçilirse
   açıklama da zorunlu.

2. **Sahibi kaydedilir ve kimlik istekten okunmaz.** Düzeltmeyi yapan, token'daki
   `sub` alanından alınır. Gövdeye bir "kim" alanı koymak, denetim izini istemcinin
   doldurduğu bir alana bağlamak olurdu.

3. **Stok kaydı yoksa hata (404).** Mal kabulü kaydı kendisi açar; düzeltme açamaz.
   Var olmayan bir sayı düzeltilemez.

4. **Ayrılmış miktara dokunulmaz.** Düzeltme yalnızca satılabilir miktarı değiştirir.

İdempotentlik ADR-0019'daki desenin aynısı: istemcinin verdiği `adjustmentId`
birincil anahtar, karar `INSERT ... ON CONFLICT DO NOTHING` ile veritabanında.

## Değerlendirilen alternatifler (Considered options)

- **Eksi miktarlı makbuz (`stock_receipts`'e negatif satır)** — *reddedildi*
  - Artı: yeni tablo yok, yeni uç yok, tek satırlık değişiklik.
  - Eksi: iki farklı olayı tek kovaya atardı. "Bu ay ne kadar mal girdi" sorusu bir
    daha cevaplanamazdı, çünkü kırılan mallar da aynı tabloda eksi olarak dururdu.
  - Eksi: makbuzun gerekçe alanı yok ve olmamalı — "mal geldi" zaten kendi gerekçesi.

- **Serbest metin gerekçe** — *reddedildi*
  - Artı: esnek, hiçbir listeyi bakımda tutmak gerekmez.
  - Eksi: "kırık", "kirik", "hasarlı", "damaged" hepsi ayrı değer olur ve denetim izi
    okunamaz hâle gelir. Denetim izinin değeri okunabilmesinde.

- **Mutlak atama (`PUT /stock/{sku}` → "stok artık 95")** — *reddedildi*
  - ADR-0019'daki gerekçelerin aynısı: eşzamanlı iki işlemden biri sessizce kaybolur,
    ve "ne kadar değişti" bilgisi hiç kaydedilmez. Sayım düzeltmesinde ikincisi daha
    da önemli: kaydedilmesi gereken şey **fark**tır.

- **Ayrılmış miktarı da düşürebilmek** — *reddedildi*
  - Rezerve mal bir müşteriye söz verilmiştir. Sayım farkını oradan düşmek, siparişi
    olan birinin malını sessizce almak olur. Rezerve mal gerçekten kaybolduysa doğru
    cevap stoğu düzeltmek değil, **o siparişi iptal etmektir** — ve o, saga'nın işidir.

- **Eksiye düşen düzeltmeyi sıfıra yuvarlamak** — *reddedildi*
  - "5 tane kırıldı" denildiğinde elde 3 varsa, gerçek dünyada bir şey daha yanlış
    demektir. Yuvarlamak o ikinci hatayı gizler. İstek reddedilir.

## Sonuçlar (Consequences)

- **Olumlu:** Stok artık her iki yönde de hareket edebiliyor ve **her hareketin bir
  kaydı var**: giriş makbuzda, düzeltme kendi defterinde.
- **Olumlu:** Denetim izi okunabilir (`GET .../adjustments`). Okunamayan bir iz, iz
  değildir.
- **Olumlu:** Düzeltmeyi yapanın kimliği istemciye bırakılmıyor.
- **Olumsuz / ödün:** Gerekçe listesi zamanla yetmeyebilir. Her yeni değer, geçmiş
  kayıtları yeniden yorumlamayı gerektirir; eklemek ucuz görünür, geriye dönük olarak
  pahalıdır. Bu yüzden liste kasten kısa tutuldu ve `OTHER` var.
- **Olumsuz / ödün:** Düzeltme **olay yayınlamıyor**. Outbox düzeneği serviste var ama
  tüketicisi yok; ADR-0019'daki gerekçenin aynısı. Gerçek bir sistemde muhasebe bunu
  dinlerdi.
- **Takip / risk:** Denetim izi tek seferde en fazla 50 kayıt dönüyor ve sayfalama yok.
  Yüzlerce düzeltmesi olan bir SKU'da eski kayıtlar bu uçtan görünmez.
- **Takip / risk:** Yetki tek kademeli — `ADMIN` olan herkes sınırsız düzeltebilir.
  Gerçek bir kurulumda büyük düzeltmeler ikinci bir onay isterdi.
