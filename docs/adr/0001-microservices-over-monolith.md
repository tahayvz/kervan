# ADR-0001: Mikroservis mimarisi (modüler monolit değil)

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
E-ticaret akışının parçaları (katalog, sipariş, ödeme, stok, arama) **çok farklı
değişim hızlarına ve ölçeklere** sahip. Katalog nadiren yazılır ama çok okunur;
sipariş yüksek yazma hacmine sahip; arama tamamen farklı bir veri deposu (ES) ister.
Ayrıca projenin **açık amacı**, kurumsal ölçekte mikroservis desenlerini (event-driven,
Saga, CDC, dağıtık gözlem) uygulamalı göstermektir.

## Karar
Sistem **mikroservislere** bölünür; her bounded context kendi servisi ve **kendi
veritabanına** sahiptir (database-per-service). Servisler yalnızca **API** veya
**event** üzerinden konuşur; paylaşılan veritabanı yasaktır.

## Değerlendirilen alternatifler
- **Modüler monolit:** Basit deploy, kolay lokal geliştirme, transaction kolaylığı.
  Ancak projenin öğrenme/gösterme hedefiyle (event-driven, Saga, CDC, dağıtık trace)
  çelişir; bu desenler monolitte doğal olarak ortaya çıkmaz. → **Elendi.**
- **Nano-servis (aşırı bölme):** Gereksiz operasyonel yük, dağıtık monolit riski.
  → **Elendi.**
- **Mikroservis (seçilen):** Bağımsız ölçek/deploy, teknoloji çeşitliliği, gerçek
  kurumsal desenlerin sergilenmesi.

## Sonuçlar
- **Olumlu:** Servisler bağımsız ölçeklenir/deploy edilir; her biri en uygun veri
  deposunu kullanır (Postgres/ES); kurumsal desenler doğal olarak devreye girer.
- **Olumsuz / ödünler:** Dağıtık sistemin bedeli — network, dağıtık tutarlılık,
  gözlemlenebilirlik karmaşası. Bu bedel bilinçli kabul edilir ve ADR-0004/0005
  ile yönetilir.
- **Risk:** "Dağıtık monolit"e kaymamak için sınırlar (context) katı tutulur;
  servis-servis senkron zincirlerden kaçınılır (ADR-0003).
