# ADR-0004: Dual-write problemi — Transactional Outbox + Debezium

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Bir servis iş verisini DB'ye yazıp aynı anda bir event yayınlamak ister
(örn. `orders`'a insert + `OrderCreated` event). DB ve Kafka **ayrı sistemler**
olduğundan bu iki yazım **atomik değildir** ("dual-write" problemi):

- DB commit olur, sonra Kafka publish patlarsa → **event kaybolur**.
- Kafka publish olur, sonra DB rollback olursa → **hayalet event**.

İki durumda da veri tutarsızlaşır. E-ticarette bu, "para alındı ama sipariş yok"
gibi felaketlere yol açar.

## Karar
**Transactional Outbox** deseni + **Debezium CDC**:
1. Servis, iş verisini ve event'i **aynı DB transaction**'ında yazar: biri `orders`,
   biri `outbox` tablosuna. Tek transaction → ya ikisi de ya hiçbiri (atomik).
2. **Debezium**, Postgres'in **WAL**'ını (write-ahead log) dinler; `outbox`
   tablosuna düşen satırları Kafka topic'ine taşır.
3. Uygulama kodu **Kafka'ya hiç dokunmaz** — sadece DB'ye yazar.

```
BEGIN TX
  INSERT orders (...)
  INSERT outbox (aggregate=Order, type=OrderCreated, payload=...)
COMMIT
     │  (Debezium WAL'ı okur)
     ▼
  Kafka: order.events
```

## Değerlendirilen alternatifler
- **"Commit sonrası publish":** `@TransactionalEventListener(AFTER_COMMIT)` ile
  commit'ten sonra Kafka'ya gönder. Basit ama commit ile publish arası uygulama
  çökerse **event kaybolur**. Garanti vermez. → **Elendi.**
- **2PC (XA / dağıtık transaction):** DB + Kafka'yı tek transaction'a sok. Ölçeklenmez,
  Kafka XA'yı iyi desteklemez, kilitlenme/performans sorunu. → **Elendi.**
- **Polling Publisher (Outbox'u uygulama sorgular):** Outbox'u bir scheduler ile
  okuyup publish et. Çalışır ama DB'ye sürekli polling yükü + gecikme. Debezium
  (log-tabanlı) daha verimli ve gerçek-zamanlıya yakın. → **Elenmedi ama Debezium
  tercih edildi.**
- **Outbox + Debezium (seçilen):** Atomik yazım + log-tabanlı, düşük gecikmeli,
  uygulamaya sıfır Kafka bağımlılığı.

## Sonuçlar
- **Olumlu:** Event kaybı/çiftlenmesi kökten çözülür; uygulama Kafka'dan bağımsız
  test edilebilir; "en az bir kez" (at-least-once) teslim garantisi.
- **Olumsuz / ödünler:** At-least-once nedeniyle **tüketiciler idempotent** olmak
  zorunda (ADR-0005 ile birlikte ele alınır); Debezium + connector operasyonel
  bileşen ekler; outbox tablosu temizliği (retention) gerekir.
- **Risk:** Debezium connector yapılandırması (WAL, replication slot) doğru
  kurulmalı; Faz 3'te Testcontainers ile uçtan uca doğrulanır.
