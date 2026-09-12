# ADR-0014: Metrikler çekilir, actuator ayrı portta durur

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

İzleme (ADR-0012) bir isteğin nerede takıldığını gösteriyor. Gösteremediği şey
şu: "bu ne sıklıkta oluyor?" Tek bir iz, bir örnektir. Kaç sipariş bekliyor,
hata oranı dün neydi, stok ne sıklıkta yetmiyor — bunlar **metrik** sorularıdır.

İki karar gerekiyordu ve ikisi de geri dönüşü pahalı:

1. Metrik dışarı mı gönderilir, yoksa dışarıdan mı okunur?
2. Ölçüm ucu hangi portta durur? Ağ geçidi dışarıya açılan tek süreç ve metrik
   ucu yönlendirme kimliklerini, istek hızlarını, JVM ve bağlantı havuzu
   ayrıntılarını yayıyor.

## Karar (Decision)

Metrikler **çekilir (pull)**: uygulama `/actuator/prometheus` ucunu açar,
Prometheus gelip okur. Ölçüm ucu **iş trafiğinin portunda durmaz**; her serviste
ayrı bir yönetim portu vardır (iş portu + 1000).

Yönetim portunu koruyan şey **ağdır, token değil**: compose onu dışarı açmaz.

## Değerlendirilen alternatifler (Considered options)

- **Pull — Prometheus okur (seçilen)**
  - Artı: uygulama, Prometheus kapalıyken ne yapacağına karar vermek zorunda
    değil. Gönderen taraf olsaydı "biriktir mi, at mı, bekle mi" sorusunu her
    servis kendi çözerdi.
  - Artı: "servis ayakta mı" sorusunun cevabı bedava gelir. Kazıma başarısızsa
    `up` metriği 0 olur; bunu servisin kendi metrikleri değil, Prometheus üretir.
  - Eksi: Prometheus her hedefi tanımak zorunda. Bugün elle yazılı liste;
    Kubernetes'te (Faz 9) servis keşfi bunu devralır.

- **Push — OTLP ile toplayıcıya göndermek**
  - İzler zaten böyle gidiyor, tutarlı olurdu.
  - Eksi: gönderim başarısızlığı uygulamanın sorunu hâline gelir; kuyruk, geri
    basınç ve yeniden deneme mantığı her servise girer.
  - Eksi: `up` gibi bir "hedef cevap vermiyor" sinyali kendiliğinden oluşmaz.

- **Actuator'ı iş portunda bırakıp kimlik doğrulaması istemek**
  - Prometheus'un token alması gerekirdi: kazıyıcıya Keycloak kimliği vermek,
    yenilemek, döndürmek.
  - Ayrıca tutarsız olurdu: `/actuator/prometheus` açılırken
    `/actuator/metrics` kapalı kalırdı — ikisi aynı veriyi yayıyor.

- **Actuator'ı iş portunda bırakıp herkese açmak**
  - Arka servisler için savunulabilirdi (dışarıya açılmıyorlar). Ağ geçidi için
    değil: 8000 dışarıya açık.

## Sonuçlar (Consequences)

- **Olumlu:** Ölçüm ve sağlık uçları tek kuralla yönetiliyor — "iş portunda
  değiller". Yeni bir actuator ucu açmak iş portunun güvenlik ayarını hiç
  ilgilendirmiyor; yanlışlıkla açılacak bir şey kalmıyor.
- **Olumlu:** `@ConditionalOnManagementPort(DIFFERENT)` emniyet kilidi: iki port
  birleştirilirse actuator'ı serbest bırakan bean **kaybolur** ve uçlar yeniden
  kimlik doğrulamasına tabi olur.
- **Olumsuz / ödün:** Servis başına iki port. Dockerfile'lar, compose ve
  Prometheus ayarı ikisini de bilmek zorunda.
- **Olumsuz / ödün:** Yönetim portunda kimlik doğrulaması yok. Düşman bir ağda
  çalışılacaksa mTLS ya da kimlik doğrulama eklenmeli; bugünkü koruma ağ
  seviyesinde.
- **Takip / risk:** Etiket (label) kardinalitesi. Her farklı etiket değeri ayrı
  bir zaman serisidir. Bu yüzden başarısızlık **sebebi** etiket yapılmadı: sebep
  serbest metindir ve her yeni metin depoyu büyütürdü. Sebep log'da durur.
- **Takip / risk:** Veritabanından okunan gauge'ler (`kervan_outbox_pending`,
  `kervan_saga_in_flight`) her kopyada **aynı** değeri bildirir. Panolarda
  `sum()` değil `max()` kullanılmalı; `sum()` değeri kopya sayısıyla çarpar.

## İlgili

- [ADR-0012](0012-observability-instrumentation.md) — izleme ve enstrümantasyon
- [ADR-0004](0004-transactional-outbox-debezium.md) — outbox metriklerinin
  yalnızca uygulama içi yayıncı açıkken üretilmesinin sebebi
