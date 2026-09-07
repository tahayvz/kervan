# ADR-0008: Olay biçimi — Avro + Schema Registry (JSON değil)

- **Durum:** Accepted
- **Tarih:** 2026-09-06
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Faz 3'te olaylar servis sınırının dışına çıkıyor. Bir olayı yayınlayan servis ile onu
tüketen servis ayrı ayrı dağıtılır; ikisi hiçbir zaman aynı anda güncellenmez. Bu
yüzden şu iki durum kaçınılmazdır:

- yeni tüketici, eski üreticinin yazdığı olayları okur;
- eski tüketici, yeni üreticinin yazdığı olayları okur.

Olay biçimi bunu kaldırmıyorsa, dağıtım anında olaylar okunamaz hâle gelir ve hata
üretim ortamında ortaya çıkar.

İkinci mesele para. `totalAmount` alanı `double` olarak taşınırsa kuruş yuvarlama
hatası birikir; e-ticarette bunun karşılığı yanlış tutardır.

## Karar
Olaylar **Apache Avro** ile serileştirilir ve şemalar **Confluent Schema Registry**'de
tutulur. Şema dosyaları tek yerde, `event-contracts` modülünde durur; Java sınıfları
onlardan üretilir, elle yazılmaz.

Uyumluluk modu **BACKWARD**: yeni şema, eski şemayla yazılmış veriyi okuyabilmelidir.
Bu, "önce tüketiciyi güncelle, sonra üreticiyi" sırasını mümkün kılar.

## Değerlendirilen alternatifler

- **JSON (şemasız)** — Bugün en kolayı. Alan adları her mesajda tekrar taşınır, boyut
  büyür. Asıl sorun şu: sözleşme hiçbir yerde yazılı değildir. Bir alanın adı
  değiştiğinde derleme geçer, testler geçer, hata yalnızca çalışan sistemde görülür.
  Elendi.

- **JSON Schema + Schema Registry** — Sözleşme yazılı hâle gelir; Registry uyumluluğu
  denetler. Ama mesaj hâlâ metin: boyut büyük, sayı tipleri (özellikle ondalık) belirsiz.
  Avro'nun `decimal` mantıksal tipi gibi bir karşılığı yok. Elendi.

- **Protobuf** — Avro'ya çok yakın bir seçenek; ikili, şemalı, Registry destekli.
  Avro tercih edildi çünkü şema veriyle birlikte çözümlenir (writer/reader şeması
  eşleştirmesi) ve Debezium'un Kafka Connect dönüştürücüleri Avro ile daha yaygın
  kullanılır — Faz 3'ün ikinci yarısı Debezium.

- **Avro + Schema Registry** — Seçilen. İkili ve kompakt (alan adları taşınmaz),
  şema zorunlu, uyumluluk kayıt anında denetlenir, `decimal` ve `timestamp-millis`
  gibi mantıksal tipler var.

## Sonuçlar

- **Olumlu:** Uyumsuz şema değişikliği Registry tarafından reddedilir; hata dağıtımdan
  önce görülür. Mesaj boyutu küçülür. Para `decimal` olarak taşınır, kuruş kaybolmaz.
  Ölçek veritabanındaki `NUMERIC(19,4)` ile aynı tutuldu: sözleşmenin depodan dar
  olması için bir sebep yok ve dar olsaydı 3 ondalıklı para birimleri sığmazdı.
  Şema tek yerde durduğu için "hangi sürüm doğru" tartışması ortadan kalkar.

- **Olumsuz / ödünler:** Altyapıya bir bileşen daha eklenir (Schema Registry) ve o
  bileşen üretim ortamında ayakta tutulmalıdır. Mesaj artık `kafka-console-consumer`
  ile çıplak gözle okunamaz; şemayı bilen bir araç gerekir. Derleme adımı büyür
  (şemadan kod üretimi).

- **Riski nasıl azaltıyoruz:** Uyumluluk kuralı yalnızca Registry'ye bırakılmadı.
  `event-contracts` modülündeki `SchemaEvolutionTest`, aynı kontrolü test zamanında
  yapar: varsayılanı olmayan alan eklemek ya da alan silmek CI'da kırılır, üretimde
  değil.

## İlgili kararlar
- ADR-0003 (Kafka) — olayların taşındığı omurga
- ADR-0004 (Transactional Outbox + Debezium) — olayların Kafka'ya nasıl çıktığı
