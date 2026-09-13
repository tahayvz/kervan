# ADR-0020: Saga üç gerçek servisle test edilir, CDC taşıması taklit edilir

- **Durum:** Accepted
- **Tarih:** 2026-09-13
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Saga sistemin en karmaşık parçası ve otomatik bir uçtan uca testi **yoktu**. Var
olanlar şunlardı:

| Test | Neyi doğruluyordu |
|---|---|
| `OrderSagaIntegrationTest` | Orchestrator kararları — stok/ödeme cevapları **elle** Kafka'ya konuyordu |
| `StockSagaStepIntegrationTest` | Komut geldi, stok değişti, cevap **outbox tablosuna** yazıldı |
| `PaymentSagaStepIntegrationTest` | Aynısı ödeme için |
| `SagaContractsTest` | Mesaj sözleşmesi |

Yani her yarım, karşı tarafın **elle yazılmış taklidine** karşı doğrulanmıştı. İki
taklit birbirini tutuyor olsa bile, iki **gerçek** tarafın tuttuğunu kimse
göstermemişti. Zincirin bütünü yalnızca Faz 10'da, compose üzerinde **elle**
görülmüştü.

Bir engel vardı: `order-service`'in uygulama içi bir `OutboxPublisher`'ı var ve
satırlarını Kafka'ya kendisi taşıyor; `inventory` ve `payment`'ta böyle bir sınıf
**yok** — onların cevaplarını **Debezium** taşıyor. Yani üç servis aynı JVM'de ayağa
kalksa bile cevaplar order'a hiç ulaşmaz.

## Karar (Decision)

Yeni bir test modülü (`saga-e2e-tests`) **üç servisi aynı JVM'de**, gerçek PostgreSQL
ve gerçek Kafka üzerinde ayağa kaldırır ve saga'nın üç yolunu yürütür: mutlu yol,
ödeme reddi telafisi, ve stok yetersizliği.

CDC taşıması için teste özel bir **köprü** yazıldı: `inventory` ve `payment`'ın outbox
tablolarını yoklar ve satırları konularına basar — kuralı konektör dosyasından birebir
alarak (anahtar `aggregate_id`, değer `payload` baytları olduğu gibi, `trace_parent`
→ `traceparent` başlığı).

**Yerine konan tek şey CDC taşıması; üç servisin mantığı gerçek.** Ve taklit edilen
parça, bizim kodumuz olmayan tek parça.

## Değerlendirilen alternatifler (Considered options)

- **Testin içinde gerçek Debezium (Kafka Connect + Testcontainers)**
  - Artı: zinciri tam kapatır, CDC yapılandırmasını da doğrular.
  - Eksi: Debezium imajının ARM sürümü geliştirme makinesinde çöküyor (günlük B20).
    Test yerelde **hiç** koşturulamazdı — yalnızca CI'da. Faz 8'in bütün dersi
    "çalıştır, varsayma" idi; koşturulamayan bir test o dersin tersi.
  - Eksi: Connect açılışı + konektör kaydı + günlükteki Debezium tuzakları (kalp
    atışı görevi öldürüyor, snapshot sırası, `header.converter`). En önemli test,
    aynı zamanda en kırılgan test olurdu.

- **Üç servisi ayrı süreç olarak çalıştırmak (`java -jar`)**
  - Artı: tam classpath yalıtımı; aşağıdaki üç çakışmanın hiçbiri yaşanmazdı.
  - Eksi: süreç yönetimi, port dağıtımı, açılış bekleme, log toplama. Ayrıca
    siparişi HTTP'den vermek gerekir ve bu, testin içine bir JWKS sunucusu koymayı
    gerektirir. Test, sınadığı şeyden çok kendi düzeneğiyle uğraşırdı.

- **Hiç yapmamak, sözleşme testlerini genişletmek**
  - Eksi: boşluk kapanmaz. Sözleşme "mesaj şu şekilde" der; **sıranın** doğru
    işlediğini söylemez.

## Sonuçlar (Consequences)

- **Olumlu:** Saga'nın üç yolu artık her commit'te otomatik yürüyor. Test 16 saniyede
  bitiyor ve geliştirme makinesinde de koşuyor.
- **Olumlu:** Mutasyonla doğrulandı — telafi adımı bilerek bozulduğunda **yalnızca**
  `compensationPath` kırmızıya döndü, diğer ikisi yeşil kaldı. Test ölçtüğünü
  ölçüyor ve hedefi dar.
- **Olumlu:** Yol boyunca üç gerçek çakışma çıktı (aşağıda); üçü de tek bir JVM'de
  birden fazla Spring uygulaması çalıştırmanın yapısal sonuçlarıydı ve ikisi gerçek
  bir hijyen eksikliğine işaret ediyordu.
- **Olumsuz / ödün:** Debezium'un **çalıştığı** burada doğrulanmaz — mantıksal
  çözümleme, yayın (publication), replication slot, snapshot davranışı. Bunlar yalnızca
  Faz 10'un compose koşusunda, elle görüldü.

  *Güncelleme (2026-09-13):* bu ADR ilk yazıldığında "konektör JSON'undaki bir hata CI'yı
  kırmaz" deniyordu. Artık **kısmen kırıyor**. `OutboxConnectorConfigTest` konektör
  dosyalarını sistemin geri kalanına bağlıyor: konektörün yazdığı konu ile servisin
  dinlediği konunun aynı olduğunu, okuduğu sütunların göçlerde var olduğunu, ve
  günlükteki tuzakların (kalp atışı açık, `header.converter` JSON, `snapshot.mode`)
  geri gelmediğini sınıyor. Köprü de artık kuralı elle taşımıyor, **aynı dosyadan
  okuyor** — yani taklit ile aslı arasındaki kayma kapandı.

  Ayrım net kalsın: bu, *değerlerin* doğru olduğunu söyler; *düzeneğin çalıştığını*
  değil.
- **Olumsuz / ödün:** `catalog` ve `search` bu testin dışında. Saga'ya katılmıyorlar.

## Yol boyunca gereken üç yapısal değişiklik

Üç servisi tek classpath'e koymak, daha önce görünmeyen üç **global ad alanı**
çakışmasını ortaya çıkardı:

1. **`target/<modul>.jar` bir bağımlılık değildi.** `spring-boot-maven-plugin`
   varsayılan olarak asıl artifact'ı fat jar ile **değiştirir**; sınıflar
   `BOOT-INF/classes/` altında kalır ve modül başkasına bağımlılık olamaz. Çözüm:
   `<classifier>exec</classifier>` — normal jar bağımlılık, `-exec` jar çalıştırılabilir
   olan. Dockerfile'lar artık `-exec` olanı kopyalıyor.

2. **`classpath:db/migration` global bir ad alanı.** Üç servisin de `V1__*.sql`'i
   vardı; Flyway hepsini birden görüp açılmayı reddetti
   (*"Found more than one migration with version 1"*). Göçler servis adıyla
   isimlenmiş alt klasörlere taşındı.

3. **`classpath:/application.yml` de global.** Spring ilkini bulur, diğerlerini hiç
   görmez — ve hata vermez. Belirti: `inventory` diye başlatılan uygulama
   `[order-service]` adıyla açıldı ve order şemasını inventory veritabanına uyguladı.
   Testte her uygulama kendi dosyasına (`spring.config.location`) sabitlendi; üretim
   yapılandırması değişmedi.

İlk ikisi üretim kodunda düzeltildi çünkü gerçek birer eksikti: iki modül aynı
classpath'e girdiği anda patlayacak mayınlardı. Üçüncüsü teste özel kaldı — tek
başına çalışan bir servis için `application.yml` doğru addır.
