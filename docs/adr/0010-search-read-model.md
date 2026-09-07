# ADR-0010: Arama için ayrı okuma modeli (CQRS)

- **Durum:** Accepted
- **Tarih:** 2026-09-07
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
"Nike marka, elektronik kategorisinde, 1000–2000 TL arası, adında 'kulaklık' geçen
ürünler" gibi bir sorgu üç şey ister: metin araması, çok kriterli süzme ve sonuçların
yanında sayaçlar ("Nike (12), Adidas (7)").

Katalog verisi MongoDB'de (ADR-0006) ve orada yazma için biçimlenmiş: esnek belge,
öznitelik haritası, iyimser kilitleme. Bu yapı yazma için doğru, arama için değil.

Ayrıca arama trafiği yazma trafiğinden çok daha yüksektir ve ikisinin aynı depoyu
paylaşması, birinin diğerini yavaşlatması demektir.

## Karar
Arama için **ayrı bir okuma modeli** kurulur: `search-service`, katalog değişikliklerini
dinleyip Elasticsearch'te aramaya uygun bir kopya tutar.

Bu, CQRS'in okuma tarafıdır: **veri katalogta üretilir, burada yalnızca yansıtılır.**
Servisin yazma ucu yoktur.

Beslenme kaynağı, Debezium'un MongoDB change streams'ten ürettiği akıştır (ADR-0004) —
bu servis o akışın ilk müşterisidir.

## Değerlendirilen alternatifler

- **MongoDB'de aramak** — Metin indeksi ve `$facet` ile bir yere kadar gider. Ama
  çok kriterli süzme + facet sayımı + alaka sıralaması hepsi birden istendiğinde
  MongoDB bu iş için yapılmamıştır; ayrıca arama yükü yazma yolunu etkiler.
  **Elendi.**

- **Katalog servisinin Elasticsearch'e de yazması (dual write)** — Servis hem Mongo'ya
  hem Elasticsearch'e yazar. İkisi ayrı sistem olduğu için atomik değildir: biri
  yazılır diğeri yazılmazsa arama sessizce yanlış sonuç verir. Faz 3'te bütün proje bu
  problemin karşısında konumlandı. **Elendi.**

- **Zamanlanmış toplu aktarım** — Gece katalogtan Elasticsearch'e kopyala. Basit ama
  arama gün boyu bayat kalır; fiyat değişikliği ertesi güne kadar görünmez.
  **Elendi.**

- **CDC akışından beslenen okuma modeli (seçilen)** — Katalog servisi arama diye bir
  şey olduğunu bilmez; değişiklikler kendiliğinden akar. Yeni bir okuma modeli
  (öneri motoru, veri ambarı) eklemek katalog servisine dokunmayı gerektirmez.

## Sonuçlar

- **Olumlu:** Arama, katalogun yazma yolundan tamamen ayrık. İndeks türev veridir:
  kaybolursa akış baştan oynatılarak yeniden kurulur, veri kaybı olmaz. Katalog
  servisi tüketicilerini bilmez.

- **Olumsuz / ödünler:**
  - **Nihai tutarlılık.** Katalogtaki bir değişiklik aramada anında görünmez; aradaki
    gecikme CDC ve indeksleme süresidir. Ürün fiyatı güncellendiğinde arama sonucu
    kısa süre eski kalabilir. Arama için kabul edilebilir, sipariş için olmazdı.
  - **Sözleşmesiz kaynak.** Bu akış bir domain olayı değil, katalog belgesinin ham
    kopyasıdır. Katalogta bir alan adı değişirse `search-service` kırılır. Bedel
    bilerek kabul edildi (ADR-0004); kırılmanın tek yerde olması için çeviri tek
    sınıfta durur.
  - Ayakta tutulacak bir bileşen daha (Elasticsearch).

- **İdempotentlik ve sıra:** Teslimat en az bir kezdir ve olaylar tekrar gelebilir.
  Ayrı bir düzenek kurulmadı: kayıt, kaynak belgenin **kendi sürümüyle** yazılır ve
  Elasticsearch daha küçük bir dış sürümü reddeder. Tekrar gelen olay sonucu
  değiştirmez, geç kalmış olay yeni veriyi ezemez.

- **Alan tipleri elle tanımlanır.** Elasticsearch var olmayan bir indekse yazıldığında
  onu kendi tahminiyle kurar ve her metin alanını `text` yapar. O hâlde marka ve
  kategori analiz edilir: facet sayımı çalışmaz, süzme yanlış sonuç verir. İndeks bu
  yüzden ilk belgeden önce, tanımlı tiplerle oluşturulur.

## İlgili kararlar
- ADR-0004 (Outbox + Debezium) — akışın kaynağı ve neden ham kayıt olduğu
- ADR-0006 (Polyglot persistence) — katalogun neden MongoDB'de olduğu
