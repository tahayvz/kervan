# ADR-0012: İzleme, kod içinden (Java ajanı değil)

- **Durum:** Accepted
- **Tarih:** 2026-09-11
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Yedi servis var ve bir sipariş bunların üçünden geçiyor. Bir istek yavaşladığında
ya da yarıda kaldığında "nerede, kaç ms takıldı?" sorusunun cevabı hiçbir tek
servisin log'unda yok. Cevap ancak **izleme** (tracing) ile verilebilir: isteğin
her adımı bir *span*, tüm zincir bir *trace*.

İzleme verisini üretmek için uygulamaya ölçüm noktası koymak gerekir
(*enstrümantasyon*). Bunun iki yolu var ve seçim geri dönüşü pahalı: biri
çalışma zamanına, diğeri derlemeye bağlanır.

Kısıtlar:

- Servisler Spring Boot 3.4. Spring, HTTP isteği / Kafka mesajı / repository
  çağrısı için zaten **observation** üretiyor; veri kaynağı mevcut.
- Bu depoda her davranışın testi var. Enstrümantasyonun test edilememesi,
  "çalışıyordu" diyen ama kanıtlayamayan bir katman bırakır.
- İzleme, arıza anında bakılan şeydir. Kendisi arıza kaynağı olmamalı.

## Karar (Decision)

Enstrümantasyon **kod içinde** yapılır: `micrometer-tracing-bridge-otel` +
`opentelemetry-exporter-otlp`. Java ajanı (`-javaagent`) kullanılmaz.

Span'ler doğrudan Jaeger'a değil, **OpenTelemetry Collector**'a gönderilir.
Uygulama tek bir adres bilir.

## Değerlendirilen alternatifler (Considered options)

- **Micrometer köprüsü (seçilen)** — Spring'in ürettiği observation'lar
  OpenTelemetry span'ine çevrilir.
  - Artı: kod içinde olduğu için testten geçirilebilir; hangi span'in nereden
    geldiği okunabilir; imaja fazladan bir şey girmez.
  - Artı: Spring'in kendi desteklediği yol. Sürüm uyumu Boot'un sorunu.
  - Eksi: otomatik kapsam ajandan dar. Mongo/Elasticsearch/Redis sürücülerinin
    iç çağrıları kendiliğinden span üretmez; istenirse tek tek açılır.

- **OpenTelemetry Java ajanı** — `-javaagent` ile bayt kodu çalışma anında
  değiştirilir.
  - Artı: kod değişmeden en geniş kapsam; JDBC, Mongo, Redis, Elasticsearch dahil.
  - Eksi: **test edilemez.** Ajan yalnızca gerçek çalıştırmada devrededir; CI'da
    doğrulanamaz. Bu depoda bir davranışın testi yoksa o davranış yoktur.
  - Eksi: gizli. Span'ler "bir yerden" gelir; yanlış olduğunda hata ayıklamak
    kütüphanenin içine bakmayı gerektirir.
  - Eksi: her Dockerfile'a ajan indirme adımı ve her servise JVM parametresi ekler.

- **İkisi birden** — ajan altyapı katmanı için, köprü uygulama katmanı için.
  - Aynı çağrı iki kez ölçülür, span ağacı çiftlenir. İki ayrı yapılandırma
    kaynağı, tek bir soruya iki cevap.

## Sonuçlar (Consequences)

- **Olumlu:** İzleme davranışı birim testinde doğrulanabiliyor
  (`TraceParentProviderTest`, `OutboxPublisherTest`).
- **Olumlu:** Servis imajları değişmedi; ajan indirme adımı yok.
- **Olumlu:** Toplayıcı araya girdiği için depo değiştirmek uygulama ayarı
  değiştirmeyi gerektirmiyor.
- **Olumsuz / ödün:** Veritabanı sürücüsü seviyesindeki gecikme kendiliğinden
  görünmüyor. "Sorgu kaç ms sürdü" sorusu bugün cevapsız; gerektiğinde
  `datasource-micrometer` ile açılır. Bunu şimdiden açmamak bilinçli: her span
  saklama maliyetidir ve henüz sorulmamış bir sorunun cevabı için ödenmez.
- **Olumsuz / ödün:** Örnekleme oranı lokalde %100. Üretimde bu oran düşürülmek
  zorunda; `KERVAN_TRACE_SAMPLE_RATE` ile ayarlanıyor.
- **Takip / risk:** Spring Boot testlerde izlemeyi **varsayılan olarak kapatır**
  (`management.tracing.enabled=false`), test sınıfı `@AutoConfigureObservability`
  taşımıyorsa. Bu yüzden izlemeyi doğrulayan testler ayrı sınıflarda tutuldu:
  anotasyon görünür durur ve silinirse test kırılır. Aksi hâlde bir izleme testi
  farkında olmadan "izleme kapalıyken ne oluyor" testine dönüşebilir.
- **Takip / risk:** Toplayıcı tek noktadan arıza gibi görünebilir. Görünmüyor:
  gönderim asenkron ve başarısızlığı uygulamaya yansımaz. Toplayıcı ölürse iz
  kaybolur, istek kaybolmaz.

## İlgili

- [ADR-0013](0013-trace-context-across-outbox.md) — izin outbox/CDC üzerinden
  taşınması
