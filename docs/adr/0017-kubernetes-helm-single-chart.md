# ADR-0017: Altı servis tek Helm paketinde, altyapı paketin dışında

- **Durum:** Accepted
- **Tarih:** 2026-09-12
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Sistem compose ile çalışıyor ve Faz 10'da uçtan uca doğrulandı. Kubernetes'e
taşımak iki soru getiriyor:

1. Altı servis nasıl paketlenir — tek paket mi, servis başına ayrı paket mi?
2. Veritabanı, Kafka, Redis aynı pakete girer mi?

Bir de compose'da hiç sorulmayan sorular var: bir pod ne zaman "hazır" sayılır,
ne zaman "yeniden başlatılmalı", kapatılırken bekleyen işe ne olur.

## Karar (Decision)

**Tek Helm paketi**, altı servis aynı şablondan üretilir; aralarındaki farklar
`values.yaml`de veri olarak durur.

**Altyapı paketin parçası değildir.** Yerel deneme için `infra.enabled` ile
kurulabilen en küçük hâli var; üretimde kapatılır.

Her serviste **iki ayrı sağlık ucu** (liveness/readiness), **düzgün kapanma** ve
**açıkça yazılmış güncelleme stratejisi** bulunur.

## Değerlendirilen alternatifler (Considered options)

- **Servis başına ayrı chart**
  - Artı: servisler bağımsız sürümlenir; büyük ekiplerde doğru olan budur.
  - Eksi: altı dosyada aynı hatayı altı kez düzeltmek. Servisler birbirinin
    neredeyse aynısı; farkları port ve birkaç ortam değişkeni.

- **Tek chart, servis başına şablon**
  - Aynı tekrar sorunu, tek dosyada.

- **Tek chart + `range` ile tek şablon (seçilen)**
  - Eksi: şablon biraz daha soyut; `values.yaml` okunmadan anlaşılmaz.

- **Altyapıyı da pakete koymak**
  - Yerelde kolaylık. Ama uygulama sürümü veritabanı sürümüne bağlanırdı: bir
    servis güncellemesi veritabanını yeniden oluşturma riski taşırdı. Üretimde
    veritabanı ya yönetilen bir hizmettir ya da kendi yaşam döngüsündedir.

- **Ham manifest (Helm yok)**
  - Altı servis × 2 kaynak = 12 dosyada aynı şeyin tekrarı; ortam farkları
    kopyala-yapıştırla yönetilirdi.

## Sonuçlar (Consequences)

- **Olumlu:** Kesintisiz güncelleme ölçüldü: güncelleme boyunca 250 isteğin
  250'si 200 döndü. Bu, readiness probe + düzgün kapanma + `maxUnavailable: 0`
  üçlüsünün birlikte çalışmasının sonucu.
- **Olumlu:** Probların **yönetim portunda** olması Faz 6b'deki ayrımın doğal
  devamı: prob trafiği iş trafiğinin hız sınırından ve kimlik doğrulamasından
  geçmiyor.
- **Olumsuz / ödün:** Tek pakette olduğu için servisler birlikte sürümleniyor.
  Bir servisi tek başına güncellemek `--set image.tag` ile mümkün ama paket
  sürümü ortak.
- **Olumsuz / ödün:** `search-service` yerel kümede kapalı. Elasticsearch'e
  bağlanamazsa hiç açılmıyor ve ARM imajı bu makinede çöküyor; Kubernetes'te
  imaj başına platform seçimi yok, kind düğümünün içinde emülasyon da yok.

### Compose'da olmayan üç kural

- **`depends_on` yoktur.** Bütün pod'lar aynı anda başlar; bağımlılığı hazır
  olmayan servis çöker ve yeniden başlatılır. Yakınsar. Bağımlılık sırası
  Kubernetes'te bir *dağıtım* değil, *dayanıklılık* meselesidir. Init container
  ile deterministik hâle getirilebilirdi; tercih edilmedi, çünkü servis zaten
  yeniden denemeyi bilmek zorunda.

- **Service yalnızca hazır pod'lara yönlendirir.** Kafka'nın KRaft controller
  adresi servis adı olarak yazılınca kilitlendi: hazır olmak için kendine
  bağlanması gerekiyordu, Service ise henüz onu yayınlamıyordu. Compose'da aynı
  ad doğrudan konteynere çözülüyor ve bu sorun hiç görünmüyor.

- **Sağlık tek soru değil, iki soru.** `liveness` yalnızca "süreç kendini
  toparlayamaz" durumuna bağlanır: veritabanı kesintisinde pod'u yeniden
  başlatmak sorunu çözmez, yalnızca ikinci bir sorun ekler.

  **Ama readiness'a bağımlılık EKLENMEDİ ve bu da bilinçli.** Spring'in
  varsayılan readiness grubu yalnızca `readinessState` içerir; veritabanı
  erişilemezken bile uç `UP` döner. Bağımlılıkları eklemek bir veritabanı
  sarsıntısında **bütün pod'ları aynı anda** trafikten çekerdi — servis kısmen
  bozuk olmaktan çıkıp tamamen kaybolurdu. Aynı gerekçe Faz 5'te Redis'i ağ
  geçidinin sağlığına dahil etmeme kararında da vardı (ADR-0011).

- **Takip / risk:** Yönetim portu **ayrı bir ClusterIP Service**'te durur; dışarı
  açılan Service yalnızca iş portunu yayınlar. İkisi tek Service'te toplanmıştı ve
  sessiz bir hata üretiyordu: Kubernetes, NodePort tipindeki bir Service'in **her**
  portuna düğüm portu atar — pinlenmemiş olana rastgele bir tane. Ölçüldü: yönetim
  portu 30276 almıştı, yani ADR-0014'ün yasakladığı şey oluyordu.
- **Takip / risk:** `preStop` beklemesi var. Pod silinirken SIGTERM ile endpoint
  kaldırma aynı anda olur ve endpoint değişikliği kube-proxy'lere asenkron yayılır;
  o pencerede gelen istek kapanmakta olan pod'a düşer. Düzgün kapanma bunun yerini
  tutmaz: o **başlamış** isteği bitirir, buradaki sorun ise **henüz başlamamış** istek.
- **Takip / risk:** Küme içinde **NetworkPolicy yok**: herhangi bir pod, herhangi bir
  servisin yönetim portuna erişebilir. Compose'da "port dışarı açılmıyor" gerçek bir
  koruma idi; Kubernetes'te varsayılan ağ her şeye açıktır. Gerçek bir kurulumda
  yönetim portunu yalnızca izleme ad alanına açan bir NetworkPolicy gerekir.
  (kind'in varsayılan CNI'ı NetworkPolicy'yi zaten uygulamaz, bu yüzden burada
  yazmak yanıltıcı bir güvenlik hissi verirdi.)
- **Takip / risk:** CPU limiti bilerek konulmadı; CPU sıkıştırılabilir bir
  kaynaktır ve limit, boş CPU varken bile JVM'de uzun duraklamalar üretir.
  Bellek limiti ise şart ve `MaxRAMPercentage` ile birlikte verilmeli — yoksa
  JVM limitten habersiz heap büyütür ve konteyner `OOMKilled` olur.

## İlgili

- [ADR-0014](0014-metrics-pull-and-management-port.md) — yönetim portu
- [ADR-0004](0004-transactional-outbox-debezium.md) — altyapının ayrı yaşam döngüsü
