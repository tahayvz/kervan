# ADR-0002: Mono-repo + Maven multi-module

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Birden çok servis + paylaşılan sözleşmeler (event Avro şemaları, ortak yardımcılar)
var. Bağımlılık sürümlerinin (Spring Boot, Kafka, Testcontainers) tüm servislerde
**tutarlı** olması gerekir. Tek kişilik/öğrenme bağlamında operasyonel yük düşük olmalı.

## Karar
Tek bir **mono-repo** kullanılır. Yapı, bir **parent POM** altında **Maven
multi-module**'dür. Ortak sürümler `dependencyManagement` (BOM) ile parent'ta
merkezîleşir; her servis kendi modülüdür.

```
kervan/                (parent pom — packaging: pom)
├── platform-bom/      (ortak dependencyManagement — opsiyonel)
├── event-contracts/   (Avro şemaları, paylaşılan event tipleri)
├── catalog-service/
├── order-service/
└── ...
```

## Değerlendirilen alternatifler
- **Poly-repo (her servis ayrı repo):** Gerçek kurumsal büyük ölçekte yaygın; ekip
  bağımsızlığı yüksek. Ancak tek kişilik projede sürüm koordinasyonu ve ortak
  sözleşme paylaşımı zorlaşır, CI kurulumu çoğalır. → **Elendi (bu ölçek için).**
- **Gradle multi-project:** Geçerli bir alternatif; build performansı iyi. Ancak
  hedef iş ilanlarında Maven daha baskın ve okunması daha standart. → **Elenmedi
  ama Maven tercih edildi.**
- **Maven multi-module (seçilen):** Merkezî sürüm yönetimi, tek `mvn` ile tüm
  reaktör, ortak modül paylaşımı kolay.

## Sonuçlar
- **Olumlu:** Tek yerden sürüm yönetimi; ortak `event-contracts` modülünü tüm
  servisler paylaşır; tek komutla tüm proje derlenir/test edilir; atomik commit.
- **Olumsuz / ödünler:** Repo büyüdükçe CI süresi artabilir (modül-bazlı build ile
  hafifletilir); gerçek çok-ekipli bağımsızlık poly-repo kadar olmaz.
- **Not:** Faz 8'de GitHub Actions, değişen modüle göre seçici build yapacak.
