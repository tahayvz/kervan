# ADR-0015: Log dosyaya yazılır, taşımayı toplayıcı yapar

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Gözlemlenebilirliğin üçüncü ayağı. İz "nerede takıldı", metrik "ne sıklıkta"
diyor; log "tam olarak ne oldu" diyor. Yedi servis kendi log'unu kendi yerine
yazarken bir siparişin hikâyesini toplamak elle yapılan bir iş.

İki karar gerekiyordu:

1. Log'u Loki'ye kim gönderir — uygulamanın kendisi mi, ayrı bir toplayıcı mı?
2. Bir log satırı hangi biçimde yazılır ve hangi alan onu ize bağlar?

## Karar (Decision)

Uygulama log'u **dosyaya JSON olarak** yazar ve Loki'yi hiç tanımaz. Ayrı bir
toplayıcı (**Grafana Alloy**) dosyayı okuyup Loki'ye taşır.

Konsol **insan için** okunur kalır; JSON yalnızca dosyaya gider.

Etiket olarak sadece `service` ve `level` kullanılır. **İz kimliği etiket
değildir**; satırın içinde durur ve Grafana onu sorgu anında çıkarır.

## Değerlendirilen alternatifler (Considered options)

- **Dosya + toplayıcı (seçilen)**
  - Artı: log'un nereye gideceği bir **işletme kararıdır**, uygulama kararı
    değil. Loki'yi değiştirmek tek bir konteyner ayarı değiştirmek demek.
  - Artı: Loki erişilemezken uygulama etkilenmez. Dosya yazılmaya devam eder,
    toplayıcı geri geldiğinde kaldığı yerden okur.
  - Artı: Spring Boot 3.4'ün kendi yapılandırılmış log desteği yeterli; ek
    kütüphane yok.
  - Eksi: servisler bu makinede çalışırken dosyanın nereye düştüğüne dikkat
    etmek gerekiyor (`infra/docker/observability/README.md`).

- **Uygulamadan doğrudan Loki'ye göndermek** (loki4j gibi bir appender)
  - Artı: dosya yok, kurulum tek bağımlılık.
  - Eksi: uygulama log deposunu **tanır**. Loki değişirse altı serviste kod
    değişir.
  - Eksi: Loki erişilemezken ne yapılacağı uygulamanın sorunu olur: biriktir mi,
    at mı, bekle mi? Aynı soruyu metrik tarafında da sormuştuk (ADR-0014) ve aynı
    cevabı verdik: bu karar uygulamaya ait olmamalı.

- **Konteyner çıktısını Docker soketinden toplamak**
  - Bu depoda çalışmaz: servislerin yalnızca biri konteynerde, diğerleri
    makinede. Ayrıca toplayıcıya Docker soketi vermek gereksiz bir yetki.

- **Promtail**
  - Bu işin eski aracı; kullanımdan kalktı. Halefi Alloy.

- **İz kimliğini etiket yapmak**
  - Cazipti: `{traceId="..."}` ile doğrudan sorgulanırdı.
  - Eksi: her istek yeni bir etiket değeri üretir ve Loki her biri için ayrı bir
    akış açar. Bu, metrik tarafında sebebi etiket yapmamakla önlediğimiz
    kardinalite sorununun birebir aynısı (ADR-0014). Etiket kümeleri **sonlu**
    olmalı.

## Sonuçlar (Consequences)

- **Olumlu:** Bir log satırından o isteğin tüm yolculuğuna tek tıkla geçiliyor:
  Grafana'daki türetilmiş alan `traceId`'yi yakalayıp Jaeger'a bağlıyor. Üç ayak
  tek kimlikle birleşmiş oluyor.
- **Olumlu:** Log deposu değişebilir. Uygulama tarafında değişecek tek şey yok.
- **Olumsuz / ödün:** Dosya yolu artık bir işletim ayrıntısı. Yanlış dizine
  yazılırsa log sessizce toplanmaz — hata vermez, sadece Loki boş kalır.
- **Olumsuz / ödün:** Alan adı `traceId`, ECS sözlüğündeki `trace.id` değil.
  Değer ECS biçimlendiricisinden değil Micrometer'ın MDC'sinden geliyor ve MDC
  anahtarları olduğu gibi aktarılıyor. Adı zorlamak yerine gerçeği kabul edip
  Grafana ayarı ona göre yazıldı.
- **Takip / risk:** O ad iki ayrı dosya arasında bir **sözleşme**: biri Java,
  diğeri Grafana ayarı. Derleyici ikisini bağlamaz. `StructuredLoggingTest`
  depodaki gerçek Grafana ayarını okuyup desenini gerçek bir log satırına
  uyguluyor; ad kayarsa test kırılıyor.
- **Takip / risk:** Log satırı **tek satır** olmalı. Çok satıra yayılan bir kayıt
  (biçimlenmemiş bir yığın izi gibi) toplayıcı için bozuk satırlardır; test her
  satırın tek başına geçerli JSON olduğunu doğruluyor.

## İlgili

- [ADR-0012](0012-observability-instrumentation.md) — izleme
- [ADR-0014](0014-metrics-pull-and-management-port.md) — metrik, kardinalite
