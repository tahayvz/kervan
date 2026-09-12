# Sentetik yük ve gözlem (Faz 10)

Bu fazın amacı **kapasite ölçmek değil**. Faz 6'da kurulan gözlemlenebilirliğin ve
Faz 7'deki korumaların gerçekten çalıştığını görmek.

O zamana kadar her şey teoriydi: panolar boştu, devre kesici hiç açılmamıştı, saga
hiç yük altında çalışmamıştı. Sistem ilk kez **tamamı bir arada** ayağa kalkıyor.

---

## 1. Her şeyi başlat

```bash
docker compose -f infra/docker/docker-compose.yml up -d --build
```

İlk çalıştırmada imajlar derlenir. Dockerfile'lar Maven deposunu BuildKit önbelleğine
aldığı için **ikinci** derleme çok daha hızlıdır; önbellek olmadan altı servis yerelde
saatler sürüyordu.

Hazır olmasını bekle (Keycloak en yavaşı):

```bash
docker compose -f infra/docker/docker-compose.yml ps
```

## 2. Veriyi tohumla

```bash
scripts/seed.sh 25
```

Ürünleri **ağ geçidi üzerinden** oluşturur: kimlik doğrulama, yetki, doğrulama ve olay
yayını da böylece çalışmış olur. Veritabanına doğrudan yazsaydık tohumlama sistemin
çalıştığına dair hiçbir şey kanıtlamazdı.

> **Stok tek istisna.** `inventory-service`'in stok **girişi** için bir ucu yok — stok
> yalnızca düşürülebiliyor, artırılamıyor. Bu gerçek bir eksik; betik SQL ile geçiyor
> ve yol haritasında açık iş olarak yazılı.

Arama indeksinin dolması birkaç saniye sürer (CDC → Kafka → Elasticsearch):

```bash
curl -s 'http://localhost:8000/api/v1/search/products?q=Nike' | head -c 300
```

## 3. Yükü çalıştır

```bash
docker compose -f infra/docker/docker-compose.yml --profile load run --rm k6
```

Profil arkasında: `up -d` ile kendiliğinden çalışmaz, çünkü yük üretmek açıkça
istenen bir eylemdir.

İki senaryo aynı anda döner:

| Senaryo | Ne yapar | Neden |
|---|---|---|
| `browsing` | Katalog listeler, arama yapar | Gerçek trafikte okuma, yazmadan kat kat fazladır |
| `ordering` | Saniyede N sipariş verir | Saga'yı, outbox'ı ve ödemeyi çalıştıran pahalı iş |

Ayarlar: `KERVAN_VUS`, `KERVAN_DURATION`, `KERVAN_ORDER_RATE`.

> Adlar bilerek `K6_` ile başlamıyor. O önek k6'nın kendi ayarlarına ait:
> `K6_VUS` verirsen k6 senaryoları **tamamen ezer** ve `function 'default' not
> found in exports` der.

Eşikler bir hedef değil **alarm**: aşılırsa koşu başarısız sayılır. Aksi hâlde "yük
testi çalıştı, herhalde iyidir" denir.

## 4. Neye bakılır

| Nereye | Ne görülür |
|---|---|
| Grafana → Kervan genel bakış | İstek hızı, hata oranı, p95; outbox gecikmesi; havada kalan saga |
| Jaeger | Bir siparişin üç servisteki yolculuğu, tek zincir |
| Grafana → Explore → Loki | `{service="order-service"}` — bir satırdan **TraceID** ile ize atla |

Sırasıyla sorulacak sorular:

1. **Outbox'ta en eski bekleyen kaydın yaşı artıyor mu?** Artıyorsa yayıncı üretimi
   yakalayamıyor demektir. Sayı değil **yaş** bakılır: kuyrukta tek kayıt olabilir ama
   o kayıt iki dakikadır bekliyordur.
2. **Havada kalan saga sayısı düşüyor mu?** Anlık yığılma normaldir; **inmeyen**
   yığılma değildir.
3. **p95 nerede yükseliyor?** Ağ geçidinde mi, arka serviste mi? Jaeger bunu söyler.
4. **Devre kesici açıldı mı?** Açıldıysa hangi sınırda ve niye?

## 5. Her şeyi durdur

```bash
docker compose -f infra/docker/docker-compose.yml --profile load down
```

Veriyi de silmek için `-v` ekle.

---

## Bilinen sınırlar

- Yük **tek makinede** üretiliyor ve sistem de aynı makinede çalışıyor. Yani ölçülen
  sayılar kapasite değil; k6 ile servisler aynı CPU'yu paylaşıyor. Bu faz "sistem ne
  kadar kaldırır" sorusunu **cevaplamaz** ve cevaplamaya çalışmamalı.
- Ödeme sağlayıcısı taklit ve gecikmesi yok. Gerçek bir sağlayıcının gecikmesi
  saga'nın en yavaş adımı olurdu; burada o etki görünmez.
- Outbox yayıncısı açık (Debezium değil). Debezium yolu da çalışır ama konektörlerin
  elle kaydedilmesini gerektirir.
