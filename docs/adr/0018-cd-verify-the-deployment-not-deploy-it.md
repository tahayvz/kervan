# ADR-0018: CD, dağıtımı *doğrular*; otomatik dağıtım yapmaz

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

CI vardı ve yeşildi: `mvn verify` 291 testi koşuyor, sonra altı imaj
derleniyordu. Buna rağmen sistem ilk kez bütünüyle çalıştırıldığında (Faz 10)
**dokuz** gizli hata çıktı; Faz 9'un gözden geçirmesi **sekiz** tane daha
buldu. Birkaç örnek:

- `api-gateway`'de `spring-boot-maven-plugin` yoktu; jar çalıştırılabilir
  değildi ("no main manifest attribute")
- `inventory` ve `payment` web uygulaması değildi; actuator ucu hiç açılmıyordu
- Keycloak realm JSON'u içe aktarılmıyordu

Hiçbirini 291 test yakalayamazdı, çünkü hiçbiri kod hatası değil. Hepsinin ortak
sebebi tek cümle: **CI imajı DERLİYOR ama ÇALIŞTIRMIYOR.**

Faz 9'da bu boşluğu kapatacak araç zaten ortaya çıkmıştı: kind kümesi tek
komutla kurulup tek komutla siliniyor ve **aynı araç CI'da da çalışıyor**.

## Karar (Decision)

Her commit'te, **atılacak bir Kubernetes kümesi kurulup Helm paketi gerçekten
uygulanır**; pod'ların hazır olması beklenir, `scripts/smoke-k8s.sh` koşar, sonra
küme silinir.

Gerçek bir ortama otomatik dağıtım **yoktur** ve bir kayıt defterine imaj
**gönderilmez**.

Üç destekleyici karar:

1. **İmajlar iş çıktısı (artifact) olarak taşınır.** Mevcut matris işi altı imajı
   paralel derliyor; doğrulama işi onları dosya olarak indirip `kind load`
   yapıyor. Tek makinede yeniden derlemek ~30 dakika sürerdi (ölçüldü:
   8+7+6+5+2+2 dk), bu yol ~2 dakika ekliyor.

2. **CI'da hiçbir şey kapatılmaz.** Yerel kümede `search-service` ve
   Elasticsearch kapalı — sebebi kod değil, geliştirme makinesinin ARM olması
   (ADR-0010 notu, günlük B20). GitHub runner'ı amd64; orada ikisi de açılıyor.
   Yani altı imajın **altısı da** çalıştırılarak doğrulanıyor.

3. **Ayrı bir `scripts/smoke-k8s.sh`.** Var olan `smoke.sh` compose'a bağlı:
   `docker exec` çağırıyor, Keycloak'tan token istiyor, gözlemlenebilirlik
   yığınını `localhost`'ta arıyor. Helm paketinde bunların hiçbiri yok.

## Değerlendirilen alternatifler (Considered options)

- **GHCR'a imaj gönderme + sürüm etiketleme**
  - Artı: dağıtılabilir, sürümlenmiş bir çıktı üretir. Gerçek bir CD adımı.
  - Eksi: **çalıştığını kanıtlamaz.** Kapattığı boşluk farklı. Doğrulama işinin
    yerine geçmez; yanına eklenir. Şu an eklenmedi: bu fazın tek bir sorusu var
    ve o soru "paket kümede gerçekten çalışıyor mu".

- **Gerçek bir ortama otomatik dağıtım**
  - Dağıtılacak yer yok. Eldeki tek sunucu başka bir üretim sitesini çalıştırıyor;
    altı servis + Kafka + Elasticsearch'ü oraya koymak hem makineyi boğardı hem
    canlıya dokunurdu. Kapsam dışı.

- **Sadece `helm lint` / `helm template`**
  - Artı: saniyeler sürer.
  - Eksi: şablonun *üretilebildiğini* kanıtlar, *çalıştığını* değil. Yukarıdaki
    dokuz hatanın hiçbirini yakalamazdı. Yine de **eklendi** — ucuz bir ön kapı
    olarak, pahalı işe girmeden şablon hatasını yakalar.

- **Doğrulama işini yalnızca etiketli sürümlerde koşturmak**
  - Artı: CI ucuz kalır.
  - Eksi: hatayı bulmayı geciktirir. Bu hataların pahalı olmasının sebebi zaten
    geç bulunmalarıydı.

## Sonuçlar (Consequences)

- **Olumlu:** "Derledi" ile "çalışıyor" arasındaki boşluk kapandı. Bir servisin
  jar'ı bozulursa, portu kayarsa, actuator'ı açılmazsa ya da paket geçersiz
  YAML üretirse iş kırmızı olur.
- **Kanıt — iş ilk koşusunda bir hata buldu.** Kafka hazır sayılmıyordu.
  Sebebi Kafka değildi: probe'un `timeoutSeconds` varsayılanı **1 saniye** ve
  kontrol komutu bir JVM başlatıyor. Yüklü bir düğümde bu asla bitmez (66 deneme,
  66 zaman aşımı). Aynı hata `mongosh` probe'unda ve servislerin HTTP
  probe'larında da vardı. Hata Faz 9'dan beri duruyordu; 291 test yeşildi, paket
  bu makinede "çalışıyordu". Onu bulan şey yeni bir test değil, **başka bir
  ortamda çalıştırılmış olmasıydı** — bu ADR'nin bütün gerekçesi. Düzeltme:
  bütün probe'lara `timeoutSeconds` açıkça yazıldı (günlük B39).
- **Olumlu:** Yerel kümedeki tek eksik (search-service) CI'da kapandı.
- **Olumsuz / ödün:** CI süresi ~13 dakikadan ~24 dakikaya çıkıyor.
- **Olumsuz / ödün:** Bu iş **uçtan uca sipariş akışını doğrulamaz.** Pakette
  Schema Registry, Debezium Connect ve Keycloak yok; saga burada kanıtlanmaz. O,
  Faz 10'da compose üzerinde kanıtlandı. Sorulan soru "yapılandırma kümede doğru
  mu", "iş mantığı doğru mu" değil.
- **Takip / risk:** Kümede kalıcı disk yok ve altyapı `emptyDir` üzerinde
  çalışıyor; bu iş bir veri taşıma (migration) hatasını göstermez.
- **Takip / risk:** "Yeniden başlatma sayısı sıfır olmalı" **doğru bir kontrol
  değil** ve betikte bilerek yok. Kubernetes'te `depends_on` olmadığı için
  Postgres'e bağlı servisler açılışta çöküp yeniden başlar ve yakınsar (ölçüldü:
  14 yeniden başlatma, hepsi `Error`). Betik bunun yerine iki ayrı soru soruyor:
  `OOMKilled` var mı, ve pod hazır olduktan **sonra** çöken var mı.
