# inventory-service

Saga'nın stok adımını yürütür: sipariş için stok ayırır, ödeme başarısız olursa
ayırmayı geri alır.

Karar gerekçeleri: [ADR-0005](../docs/adr/0005-saga-orchestration.md) (Saga),
[ADR-0009](../docs/adr/0009-saga-message-topology.md) (mesaj topolojisi).

## Stok girişi: mal kabulü (ADR-0019)

Bu servis uzun süre stoğu yalnızca **düşürebiliyordu**. Ayırma ve geri bırakma vardı,
girişi yoktu; `StockRepository.saveAll` de yalnızca günceller, olmayan satırı açmaz.
Sonucu tohumlama betiğinde görünüyordu: betik stoğu `psql` ile doğrudan bu servisin
veritabanına yazıyordu.

```
POST /api/v1/stock/{sku}/receipts   { "receiptId": "...", "quantity": 500 }
GET  /api/v1/stock/{sku}
```

**Miktar eklenir, atanmaz.** "Stok artık 500 olsun" demek, aynı anda gelen iki girişten
birini sessizce kaybetmek olurdu: ikisi de mevcut değeri okur, ikisi de kendi sonucunu
yazar, biri buharlaşır.

**`receiptId` istemcinin.** Sunucu üretseydi her yeniden deneme yeni bir makbuz olur ve
miktar iki kez eklenirdi. Tekrarı ancak tekrarlayan taraf durdurabilir.

**Yalnızca `ADMIN` — okuma da dâhil.** Kalan stok ticari bilgidir: "son 2 adet" rakibe
fiyatlama ipucu, kötü niyetliye stok tüketme saldırısı için hedef verir. Müşteriye stok
göstermek gerekirse bu uç açılarak değil, katalogda türetilmiş bir alanla ("stokta var /
yok") yapılır.

**Karar veritabanına bırakılır.** Hem makbuz yazma hem yeni SKU'nun satırını açma
`INSERT ... ON CONFLICT DO NOTHING` ile yapılıyor. "Önce sorgula, sonra yaz" ikilisinin
arasına ikinci bir istek girebilir ve ikisi de "yeni" sonucuna varabilirdi. JPA'nın
`save()`'i burada özellikle yanlış olurdu: var olan kimliği **günceller**, yani tekrar
gelen makbuz "yeni kayıt" sanılır ve miktar ikinci kez eklenirdi.

## Stok düzeltmesi: sayım farkı (ADR-0021)

Mal kabulü "dışarıdan mal geldi" der. Düzeltme farklı bir şeyi kaydeder: "bizim sayımız
yanlışmış". Biri bir **olay**, diğeri bir **iddia**.

```
POST /api/v1/stock/{sku}/adjustments   { adjustmentId, delta, reason, note? }
GET  /api/v1/stock/{sku}/adjustments
```

**Gerekçe zorunlu ve sınırlı bir listeden:** `COUNT_CORRECTION`, `DAMAGED`, `EXPIRED`,
`SHRINKAGE`, `RETURNED_TO_SUPPLIER`, `OTHER`. Serbest metin olsaydı "kırık", "kirik",
"hasarlı", "damaged" hepsi ayrı değer olur ve "bu ay ne kadar mal kırıldı" sorusu hiç
cevaplanamazdı. `OTHER` seçilirse açıklama da zorunlu — gerekçesiz bir "diğer", gerekçe
yazmamakla aynıdır.

**Düzeltmeyi yapan token'dan alınır, gövdeden değil.** Gövdeye bir "kim" alanı koymak,
denetim izini istemcinin doldurduğu bir alana bağlamak olurdu.

**Stok kaydı yoksa 404.** Mal kabulü kaydı kendisi açar; düzeltme açamaz. Var olmayan
bir sayı düzeltilemez.

**Ayrılmış miktara dokunulmaz.** Orada duran mal bir müşteriye söz verilmiştir. Sayım
farkını oradan düşmek, siparişi olan birinin malını sessizce almak olurdu. Rezerve mal
gerçekten kaybolduysa doğru cevap stoğu düzeltmek değil, o siparişi iptal etmektir.

**Eksiye düşen düzeltme reddedilir, sıfıra yuvarlanmaz.** "5 tane kırıldı" denildiğinde
elde 3 varsa gerçek dünyada bir şey daha yanlış demektir; yuvarlamak onu gizler.

**Denetim izi sayfalı.** `?cursor=` ve `?size=` (varsayılan 50, üst sınır 200);
`nextCursor` boş gelene kadar aynı değeri geri gönder. `OFFSET` kullanılmıyor: iz
ekleme yapılan bir defter, sayfa çevrilirken araya yeni kayıt girerse `OFFSET` sınırı
kaydırır ve bir kayıt iki kez görünür ya da **hiç görünmez**.

## Akış

```
kervan.inventory.commands
        │
        ├─ ReserveStock ──▶ stok ayır ──┐
        └─ ReleaseStock ──▶ geri bırak ─┤   tek transaction
                                        │
                            outbox_messages'a yaz
                                        │
                                        ▼ (Debezium)
                            kervan.inventory.events
```

Servis iş olaylarını Kafka'ya **yazmaz**. Cevabını outbox tablosuna koyar; oradan
Debezium taşır (ADR-0004). Böylece stok değişikliği ile onu duyuran olay ya birlikte
olur ya hiç olmaz.

(Tek istisna ölü mektup konusudur — aşağıda. O bir işletim kanalı, iş akışı değil.)

## Neden iki sayı: `available` ve `reserved`

`available` satılabilir miktardır, `reserved` bir siparişe tutulmuş ama henüz
çıkmamış miktardır. Tek sayı tutulsaydı, ödeme başarısız olduğunda ne kadarını geri
vereceğimizi bilemezdik — ayırma geri alınabilir bir işlemdir.

Toplam ikisinin arasında yer değiştirir; ayırma stok yaratmaz ve yok etmez.

## İdempotentlik: ayrı tablo yok

Kafka teslimatı **en az bir kez**'dir; aynı komut iki kez gelebilir. Yaygın çözüm bir
"işlenmiş mesajlar" tablosu tutmaktır. Burada tutulmuyor, çünkü işin kendisinde zaten
bir doğal anahtar var: **bir siparişin en fazla bir ayırması olur.**

Bu kural `reservations.order_id` üzerinde benzersizlik kısıtı olarak duruyor. İki
katman var:

1. Komut işlenmeden önce mevcut ayırma aranır — normal durum, sessizce geçilir.
2. İki kopya aynı anda işlerse veritabanı kısıtı devreye girer — yarış durumu.

Ayrı bir tablo, aynı kuralın ikinci bir kopyası olurdu: senkron kalması, doğru
anahtarla yazılması ve temizlenmesi gereken bir şey daha.

## "Ya hep ya hiç"

Bir kalem bile yetmezse **hiçbiri** ayrılmaz. Birinciyi ayırıp ikincide durmak,
müşteriye satılmayacak bir ürünü kilitlemek olurdu. Ayırma önce bellekte hesaplanır,
ancak tamamı geçerse yazılır.

## Başarısızlık da bir cevaptır

Stok yetmediğinde istisna dışarı **taşmaz**. Taşsaydı transaction geri alınır ve
onunla birlikte "stok yetmedi" olayı da silinirdi; saga cevap beklerken sonsuza kadar
asılı kalırdı. Bunun yerine stok hiç değiştirilmez, yalnızca başarısızlık olayı
yazılır — ikisi aynı commit'te.

## Kilitleme ve deadlock

Stok satırları `SELECT ... FOR UPDATE` ile kilitlenir: "oku–hesapla–yaz" arasında
başka bir sipariş araya giremez, iki müşteri son ürünü aynı anda alamaz.

Kilitler **her zaman SKU sırasına göre** alınır. A ve B ürünlerini içeren iki sipariş
farklı sıralarla kilit alsaydı biri A'yı diğeri B'yi tutar ve ikisi de diğerini
beklerdi — deadlock. Sabit sıra bunu yapısal olarak imkânsız kılar; yeniden deneme
mantığı gerekmez.

## Tıkanan mesaj kuyruğu kilitlemesin

Bir dinleyici istisna fırlatırsa offset ilerlemez ve aynı mesaj tekrar gelir. Hata
kalıcıysa bu sonsuza kadar sürer ve o partition'daki **arkadaki bütün mesajlar**
bekler — saga, bekleyen siparişlerle birlikte durur.

Bu teorik bir risk değil. İdempotentlik denetimini deneme amacıyla kaldırdığımda,
tekrar gelen bir komut veritabanı kısıtına takıldı ve tam olarak bu döngü oluştu;
sonraki testler zaman aşımına uğradı.

Çözüm: birkaç kez, aralığı açılarak denenir; hâlâ başarısızsa mesaj `<konu>.DLT`
konusuna taşınır ve akış devam eder.

**Ödünü:** Geçici bir arıza yeniden deneme penceresinden uzun sürerse mesaj DLT'ye
düşer ve otomatik işlenmez; oradan elle geri konması gerekir. Alternatifi, kalıcı bir
hatada kuyruğu süresiz kilitlemekti. Bekleyen siparişlerin tamamını durdurmaktansa tek
bir mesajı kenara almak tercih edildi — **DLT boş kalmadığı sürece izlenmelidir.**

Tanınmayan bir komut tipi de DLT'ye gider, ama **yeniden denenmeden**: beklemekle
tanınır hâle gelmez. İlk yazdığımda bu durumda yalnızca uyarı loglanıp mesaj
atlanıyordu; sessizce düşen bir komut, sagayı cevap beklerken asılı bırakır ve kimse
fark etmez — log satırlarına bakan olmaz. DLT en azından görülür.

## Katmanlar

```
domain/          StockItem, Reservation, ReservationLine + portlar
application/     StockReservationService (use-case'ler)
infrastructure/  JPA adaptörleri, Kafka dinleyicisi, Avro yayıncı, outbox
```

`domain` paketinde Spring, JPA ya da Avro yoktur; iş kuralları altyapı olmadan test
edilir.

## Çalıştırma

```bash
docker compose -f ../infra/docker/docker-compose.yml up -d postgres kafka schema-registry connect
```

```bash
mvn -pl inventory-service spring-boot:run
```

Servis `http://localhost:8084`. İki tür trafiği var: saga komutları Kafka'dan gelir,
stok girişi ve görüntüleme ise HTTP'den (yukarıdaki *Stok girişi* bölümü). Actuator
her zamanki gibi ayrı yönetim portunda: `http://localhost:9084`.

Konektörü kaydet:

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @../infra/docker/debezium/inventory-outbox-connector.json \
     http://localhost:8083/connectors
```

## Testler

```bash
mvn -pl inventory-service test
```

26 test: domain birim testleri (stok aritmetiği, ayırma yaşam döngüsü), use-case
testleri (mock port'larla — "ya hep ya hiç", idempotentlik, telafi) ve gerçek
PostgreSQL + Kafka container'larına karşı çalışan uçtan uca testler. Sonuncular
arasında ölü mektup yolu da var: tanınmayan bir komut gerçekten `.DLT` konusuna
düşüyor mu.

Uçtan uca test servisi içeriden çağırmaz: komutu gerçekten Kafka'ya koyar ve
dinleyicinin kendisinin almasını bekler. Konuda birden fazla komut tipi olduğu için
tipe göre yönlendirmenin çalıştığı da böylece doğrulanır.
