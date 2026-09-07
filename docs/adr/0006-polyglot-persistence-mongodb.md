# ADR-0006: Polyglot persistence — Catalog MongoDB'de, işlemsel çekirdek PostgreSQL'de

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Her servisin verisi aynı şekle sahip değil:

- **Katalog verisi heterojen ve şema-esnek:** Her kategori farklı özniteliklere sahip
  (ayakkabı → numara/renk; kitap → ISBN/yazar/sayfa; telefon → RAM/ekran/pil). İlişkisel
  bir şemada bu, ya yüzlerce nullable kolon ya da EAV (entity-attribute-value) anti-desenine
  yol açar; ikisi de bakımı ve sorgusu acı verir.
- **Sipariş/ödeme/stok verisi işlemsel ve tutarlılık-kritik:** Para ve stok, güçlü
  ilişkisel bütünlük, ACID transaction ve kesin sayısal doğruluk ister.

Tek bir veri teknolojisiyle her ikisini de "iyi" yapmak mümkün değil. Ayrıca projede
zaten iki NoSQL ailesi kullanılıyor — **Elasticsearch** (arama) ve **Redis** (cache/kilit) —
ama bunlar birincil kayıt deposu (system of record) değil.

## Karar
**Polyglot persistence** benimsenir: her servis işine en uygun veri deposunu seçer.

- **Catalog Service → MongoDB** (document store). Ürünler, kategoriye göre değişen
  esnek şemayla belge olarak saklanır. Birincil kayıt deposudur.
- **Order / Payment / Inventory → PostgreSQL** (ilişkisel, ACID). Para ve stok burada.
- **Search → Elasticsearch** (arama okuma modeli, CQRS).
- **Cache / kilit / rate-limit → Redis** (key-value).

CDC tarafında **Debezium her iki kaynağı da okur**: Postgres için WAL, MongoDB için
change streams (oplog). Böylece Outbox → Kafka akışı her iki dünyada da çalışır.

## Değerlendirilen alternatifler
- **Her şey PostgreSQL + JSONB:** Katalogun esnek özniteliklerini `jsonb` kolonda tut.
  Çalışır ve tek teknoloji sadeliği verir. Ancak: kategori-bazlı zengin sorgu/indeksleme,
  belge modelleme ve yatay ölçek MongoDB kadar doğal değil; ayrıca projenin **açık amacı**
  polyglot persistence + document DB yetkinliğini göstermek. → **Elendi.**
- **Her şey MongoDB:** Katalog için ideal ama para/stok için zayıf — çok-belge ACID
  4.0+'da var olsa da ilişkisel bütünlük ve işlemsel garantiler Postgres'te daha güçlü
  ve daha az sürprizli. Finansal veriyi document store'a koymak riskli. → **Elendi.**
- **Cassandra (wide-column):** Yazma-yoğun, zaman-serisi benzeri iş yükleri (aktivite
  akışı, bildirim geçmişi) için harika, ama katalogun sorgu esnekliği için değil ve
  operasyonel yükü yüksek. → **ASSESS'e alındı** (ileride yazma-yoğun bir servis gelirse).
- **MongoDB (katalog) + PostgreSQL (işlemsel çekirdek) — seçilen:** Her veri kendi
  doğasına uygun depoda; Debezium iki kaynağı da besliyor.

## Sonuçlar
- **Olumlu:** Katalog esnek şemayla doğal modellenir; finansal/stok verisi ACID güvencede;
  "doğru iş için doğru araç" ilkesi somut. Debezium'un çok-kaynaklı gücü sergilenir.
- **Olumsuz / ödünler:** İki veri teknolojisi = daha fazla operasyonel yük, iki farklı
  migration aracı (Postgres için **Flyway**, MongoDB için **Mongock**), iki Debezium
  connector tipi. Bu karmaşıklık bilinçli kabul edilir; kurumsal gerçeklik zaten budur.
- **Not (change streams):** Debezium MongoDB connector'ı için Mongo **replica set**
  modunda çalışmalı (tek düğümlü rs yeterli). Faz 3'te yapıldı: compose'daki Mongo tek
  düğümlü bir replica set olarak çalışıyor — küme kurmak için değil, oplog için.
  Kimlik doğrulama açıkken üyeler birbiriyle de doğrulaştığı için ortak bir anahtar
  dosyası gerekti; tek düğüm olduğundan anahtar her açılışta konteyner içinde
  üretiliyor, depoda gizli bir dosya durmuyor. Servisin bağlantı adresi değişmedi:
  sürücü tek adres verildiğinde ve URI'de `replicaSet` yazmadığında doğrudan bağlanır.
  Adrese `replicaSet=rs0` eklenirse durum değişir — üye kendini Docker ağı içindeki
  adıyla tanıtır ve o ad dışarıda çözülmez.
- **Süreklilik:** Yeni bir servis eklenirken "hangi depo?" sorusu bu ADR ışığında
  cevaplanır — varsayılan Postgres, belge-esnek/ölçek gerekçesi varsa MongoDB.
