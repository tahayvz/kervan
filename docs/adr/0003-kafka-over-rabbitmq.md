# ADR-0003: Event omurgası olarak Kafka (RabbitMQ değil)

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Servisler asenkron, gevşek bağlı iletişecek. İki ihtiyaç öne çıkıyor:
1. **Event streaming:** Event'ler kalıcı olsun; yeni bir servis (örn. yeni bir
   analytics veya arama tüketicisi) **geçmiş event'leri baştan işleyebilsin** (replay).
2. **CDC hedefi:** Debezium'un ürettiği değişiklik event'leri için doğal hedef.

Bu, klasik "message broker" (bir mesaj bir tüketiciye gidip yok olur) davranışından
farklı bir gereksinim.

## Karar
Event omurgası **Apache Kafka** olur. Şemalar **Avro + Confluent Schema Registry**
ile yönetilir.

## Değerlendirilen alternatifler
- **RabbitMQ:** Mükemmel bir **message broker** — esnek routing (exchange/queue),
  düşük gecikme, kolay kurulum. Ancak:
  - Mesajlar tüketilince **kuyruktan silinir**; log-tabanlı **replay** doğal değil.
  - **Log compaction**, uzun süreli event saklama, çok-tüketicili "her tüketici
    kendi offset'i" modeli Kafka'da yerleşik.
  - **Debezium CDC**'nin fiili hedefi Kafka.
  → E-ticaret **event-sourcing / CDC / CQRS** ihtiyaçları için elendi.
  *(Not: RabbitMQ "yanlış" değil; farklı bir araç. İş kuyruğu/RPC senaryosunda
  tercih edilebilirdi.)*
- **Redis Streams:** Hafif; ama dayanıklılık/ölçek/ekosistem Kafka seviyesinde değil.
- **Kafka (seçilen):** Kalıcı log, partition ile yatay ölçek, replay, compaction,
  Schema Registry ve Debezium entegrasyonu.

## Sonuçlar
- **Olumlu:** Event'ler kalıcı ve replay edilebilir; CQRS okuma modelleri (arama)
  Kafka'dan beslenir; Debezium ile CDC doğal; partition ile ölçek.
- **Olumsuz / ödünler:** Kafka operasyonel olarak RabbitMQ'dan ağırdır (broker,
  Zookeeper/KRaft, Schema Registry). Lokalde Compose ile, prod'da K8s ile yönetilir.
- **Kurumsal karşılığı:** "Kafka mı RabbitMQ mı?" ayrımı, mesajlaşma omurgası seçen
  her ekibin verdiği ilk karardır; gerekçesi yazılı olmadığında tartışma tekrar eder.
