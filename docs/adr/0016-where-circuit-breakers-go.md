# ADR-0016: Devre kesici nereye konur — ve nereye konmaz

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Bir arka servis yavaşladığında ne olur? Çağıran taraf bekler. Beklerken kaynak
tutar. Kaynaklar dolunca çağıranın kendisi de yavaşlar ve onu çağıran da bekler.
Tek bir yavaş bileşen böylece bütün zinciri devirir.

Klasik cevap "her çağrıya devre kesici koy". Bu projede o cevap yanlış olurdu,
çünkü çağrıların çoğu **senkron değil**. Saga Kafka üzerinden yürüyor ve orada
zaten yeniden deneme + ölü mektup düzeneği var (ADR-0009).

Yani asıl soru "devre kesici kullanalım mı" değil, **nereye koyacağımız**.

## Karar (Decision)

Devre kesici yalnızca **senkron ve dış** sınırlara konur. Bu sistemde iki tane
var:

1. **Ağ geçidi → arka servisler.** Rota başına ayrı kesici, zaman aşımı ve geri
   düşüş cevabı.
2. **payment-service → ödeme sağlayıcısı.** Kesici + dikkatli yeniden deneme +
   eş zamanlılık sınırı.

Sarma sırası içten dışa: `Bulkhead( CircuitBreaker( Retry( çağrı ) ) )`.

Zincir **kodda açıkça** kurulur (anotasyonla değil); ayarlar `application.yml`de.

## Değerlendirilen alternatifler (Considered options)

- **Her yere koymak (Kafka tüketicileri dahil)**
  - Tüketicide zaten yeniden deneme ve DLT var. İkinci bir düzenek eklemek,
    "bu mesaj neden işlenmedi" sorusuna iki ayrı cevap üretirdi. Aynı gerekçeyle
    Redis kilidi de eklenmemişti (ADR-0011).
  - Kafka tüketicisi zaten kendi hızında çalışır; kesicinin koruyacağı bir
    çağıran yok.

- **Tek ortak devre kesici**
  - Basit görünür. Ama katalog arızası sipariş trafiğini de keserdi: birbirini
    tanımayan iki servis birbirinin arızasını paylaşırdı.

- **Anotasyonla (`@CircuitBreaker`) sarmak**
  - Daha kısa. Ama davranış bir proxy'nin içinde gizlenir ve birim testinde
    doğrulanamaz. Bu depoda Java ajanı da aynı gerekçeyle elenmişti (ADR-0012).

- **Zaman aşımı koymadan yalnızca kesici**
  - **İşe yaramaz.** Kesici başarısızlık sayar; askıda kalan bir çağrı başarısız
    değildir, hâlâ beklenmektedir. Zaman aşımı olmadan yavaş bir servis kesiciyi
    hiç tetiklemez. Üretimde asıl sık görülen arıza da çökme değil yavaşlamadır.

- **Geri düşüşte önbellekten eski veri döndürmek**
  - Katalog için düşünülebilirdi. Sipariş için felaket olurdu: "siparişin yok"
    cevabı "şu an bakamıyorum" ile aynı şey değildir. Tek kural tutuldu: hiçbir
    rota uydurma veri dönmez.

## Sonuçlar (Consequences)

- **Olumlu:** Çöken bir servise yük binmeye devam etmiyor; istemci hızlı ve
  anlaşılır bir 503 + `Retry-After` alıyor.
- **Olumlu:** Kesici durumu metrik olarak yayınlanıyor ve panoda görünüyor
  (Faz 6). Devre kesici sessiz bir bileşendir — ölçülmezse arıza "sistem
  yavaşlamadı ama siparişler düştü" diye görünür ve sebebi anlaşılmaz.
- **Olumsuz / ödün:** Ayarlanacak sayılar var (pencere, eşik, açık kalma süresi)
  ve yanlış ayar iki yönde de zarar verir: çok hassas kesici sağlıklı trafiği
  keser, çok geç açılan kesici hiçbir işe yaramaz.
- **Olumsuz / ödün:** Kesici durumu **kopya başınadır**. Beş kopyanın biri açık,
  dördü kapalı olabilir. Bu bilinçli: durum paylaşılsaydı tek bir kopyanın ağ
  sorunu bütün kopyaları keserdi.

### Ödeme tarafındaki iki ayrım

- **Reddedilme başarısızlık değildir.** Sağlayıcı çalışıyor ve "hayır" diyor.
  Kesici bunu saysaydı, meşru bir ret dalgası — kampanya sonrası limit aşımları,
  bir dolandırıcılık dalgası — kesiciyi açar ve **çalışan** bir sağlayıcıya giden
  bütün ödemeler kesilirdi. Sistem, hiçbir şey bozuk değilken kendini kapatırdı.

- **Her "ulaşamadım" tekrar denenmez.** İstek hiç gitmediyse tekrar denemek
  güvenlidir. İstek gidip cevap gelmediyse tahsilat yapılmış *olabilir*;
  körlemesine tekrar denemek müşteriden ikinci kez para çekmektir. Karar hatanın
  tipinden değil **içeriğinden** verilir (`mayHaveBeenProcessed`). Yanlış tarafta
  hata yapmanın bedeli asimetriktir: siparişin iptal olması, çift çekimden çok
  daha ucuzdur.

- **Takip / risk:** Yeniden deneme, kesicinin **içinde** olmak zorunda. Dışında
  olsaydı her deneme kesiciye ayrı bir başarısızlık yazılır ve kesici üç kat
  hızlı açılırdı; ayrıca çökmüş bir sağlayıcıya her istek için üç çağrı giderdi —
  koruma yükü üçe katlardı. Sıra `ResilientPaymentGatewayTest` ile sabitlendi.

## İlgili

- [ADR-0009](0009-saga-message-topology.md) — Kafka tarafındaki yeniden deneme/DLT
- [ADR-0011](0011-redis-cache-and-rate-limit.md) — aynı gerekçeyle eklenmeyen düzenek
- [ADR-0014](0014-metrics-pull-and-management-port.md) — kesici durumunun ölçülmesi
