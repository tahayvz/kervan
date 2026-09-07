# inventory-service

Saga'nın stok adımını yürütür: sipariş için stok ayırır, ödeme başarısız olursa
ayırmayı geri alır.

Karar gerekçeleri: [ADR-0005](../docs/adr/0005-saga-orchestration.md) (Saga),
[ADR-0009](../docs/adr/0009-saga-message-topology.md) (mesaj topolojisi).

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

Servis `http://localhost:8084` (yalnızca actuator; dışa açık REST API'si yok — bu
servis mesajla konuşur).

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
