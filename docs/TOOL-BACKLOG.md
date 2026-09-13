# Kervan — Araç Backlog'u

Bu belge, projede **henüz kapatılmamış teknik boşlukları** ve her boşluğu kapatacak
adayı listeler. `docs/TECH-RADAR.md` kullanılanı anlatır; bu dosya kullanılmayanı.

**Tarih:** 2026-09-13 · **Durum:** hiçbiri başlanmadı.

---

## Sıfırıncı adım — sistemi bir kez uçtan uca çalıştır

Yığın compose ve kind ile ayağa kalkıyor ama bir siparişin akışı **gözle**
izlenmedi: outbox satırının yazılması, Debezium'un onu Kafka'ya taşıması,
saga'nın üç servisi dolaşması, Jaeger'da tek bir iz olarak görünmesi.

Faz 10'un dersi burada geçerli: *derlemek, çalıştığını kanıtlamaz.* 380 test yeşilken
dokuz gizli hata çıktı. Yeni araç eklemeden önce mevcut olanın çalıştığı görülmeli.

Hazır tur: `infra/k8s/README.md`.

---

## Zaten kullanılanlar

Bkz. `docs/TECH-RADAR.md`. Özet: Java 21, Spring Boot 3, Kafka + Avro + Schema
Registry, Debezium, PostgreSQL, MongoDB, Redis, Elasticsearch, Keycloak,
Spring Cloud Gateway, Resilience4j, OpenTelemetry + Jaeger, Prometheus + Grafana +
Loki + Alloy, Testcontainers, WireMock, Awaitility, Flyway, Mongock, Docker +
Compose, Kubernetes + Helm + Kind, GitHub Actions, CodeQL, k6.

---

## Açık boşluklar

Sıralama, boşluğun büyüklüğü ile kapatma maliyetinin oranına göre.

### 1. Statik kod analizi ve imaj taraması — SonarQube + Trivy
**Boşluk:** CI'da CodeQL var ama kapsamı dar — güvenlik desenlerine bakar.
Test kapsamı eşiği, kod tekrarı ve karmaşıklık için **kapı yok**; 8 modülde kapsam
ölçülüyor ama hiçbir şeyi durdurmuyor. Ayrıca üretilen 6 imajın **katmanları hiç
taranmıyor**: temel imajdaki bilinen açık sessizce prod'a gider.
**Aday:** SonarQube (kalite kapısı) + Trivy (imaj/bağımlılık taraması).
**Yeri:** `.github/workflows/ci.yml` + `infra/docker/`.
**Maliyet:** yarım gün.

### 2. Zamanlanmış toplu iş ve mutabakat — Spring Batch + ShedLock
**Boşluk:** Sistemde **hiç zamanlanmış iş yok**. Bunun somut sonucu şu: bir saga
yarıda kalırsa (ADR-0022'deki bekleyen düzeltmelerde zaman aşımı da yok) kimse fark
etmiyor. Order'ın kaydı ile Payment'ın kaydının tuttuğunu doğrulayan bir **gün sonu
mutabakatı** yok — ki para tutan bir sistemde bu en temel kontroldür.
İkinci boşluk: zamanlanmış iş eklendiğinde 3 kopyada 3 kez çalışır; tek kopyada
çalışmasını sağlayan bir kilit gerekir.
**Aday:** Spring Batch (parçalı okuma/işleme/yazma, yeniden başlatılabilirlik) +
ShedLock (kopyalar arası zamanlayıcı kilidi).
**Yeri:** yeni modül ya da `payment-service` içinde ayrı bir iş.
**Maliyet:** 1-2 gün.

### 3. Sürümlü yapıt deposu — Nexus (+ ikinci CI sağlayıcısı)
**Boşluk:** `event-contracts` "sürümlü sözleşme" olarak tasarlandı (ADR-0008) ama
pratikte reactor içinden çözülüyor — yani her servis **her zaman** en son hâli
kullanıyor. Eski sürümde kalmış bir tüketicinin davranışı hiç sınanmadı.
Gerçek bir yapıt deposu, sözleşme sürümlemesini iddiadan gerçeğe çevirir.
İkincil: CI tek sağlayıcıya bağlı; pipeline'ın taşınabilir olduğu doğrulanmadı.
**Aday:** Nexus (özel Maven deposu) + `Jenkinsfile` ile aynı pipeline'ın ikinci
bir koşucuda çalıştığının gösterilmesi.
**Maliyet:** 1 gün.

### 4. İlişkisel tarafta ikinci ürün — Oracle XE + PL/SQL
**Boşluk:** ADR-0006 polyglot persistence diyor ama ilişkisel aile tek ürünle
(PostgreSQL) temsil ediliyor. Ayrıca iş mantığının **veritabanı içinde** yaşadığı
model (stored procedure) projede hiç yok — bu, çoğu eski sistemle entegrasyonun
gerçeği ve sınırlarının (test edilebilirlik, sürümleme, taşınabilirlik) bilinmesi
gereken bir desen.
**Aday:** Oracle XE + küçük bir entegrasyon modülü.
**Not:** Mevcut servisler taşınmayacak. Amaç karşılaştırma, göç değil.
**Maliyet:** 1 gün.

### 5. REST/Kafka dışı dış sınır — SOAP + JMS
**Boşluk:** Projedeki bütün dış sınırlar REST ya da Kafka. Gerçekte bir e-ticaret
sisteminin bağlandığı kargo/ödeme sağlayıcıları çoğunlukla **SOAP** konuşur ve
klasik **kuyruk** kullanır. Bunun sonucu bir tasarım boşluğu: dış sistemin modelini
kendi modelinden ayıran **uyum katmanı** (anti-corruption layer) bu projede hiç
gösterilmiyor — `payment-service`'in sağlayıcısı bizim tasarladığımız bir arayüz.
**Aday:** SOAP/WSDL konuşan sahte bir kargo servisi + ActiveMQ (JMS).
**Maliyet:** 1 gün.

### 6. Farklı bir dağıtık veri modeli — Hazelcast
**Boşluk:** Dağıtık durum tek ürüne bağlı (Redis) ve tek modele: veri **dışarıda**,
uygulama ona ağdan gider. Verinin uygulamanın **içinde** yaşadığı model (embedded
veri şebekesi) denenmedi. ADR-0011 dağıtık kilidi bilerek dışarıda bıraktı;
o kararın alternatifi de ölçülmedi.
**Aday:** Hazelcast — Redis'in yerine değil yanına, gerekçesi ADR ile.
**Maliyet:** yarım gün.

### 7. Çekme tabanlı dağıtım — ArgoCD (GitOps)
**Boşluk:** ADR-0018 bilinçli olarak "dağıtımı **doğrula**, dağıtma" dedi; çünkü
dağıtılacak bir ortam yok. Ama bu, dağıtım modelinin kendisini de dışarıda bıraktı:
şu an her şey **itme** (push) modeli. Kümenin Git'teki istenen duruma kendi kendine
yakınsadığı **çekme** (pull) modeli hiç kurulmadı — kayma tespiti (drift detection)
ve geri alma bu modelde bedava gelir.
**Aday:** ArgoCD + mevcut Helm paketi, kind üstünde.
**Yeri:** `infra/k8s/argocd/`.
**Maliyet:** 1 gün.

### 8. Orkestrasyonun alternatifi — süreç motoru (BPMN)
**Boşluk:** ADR-0005 saga'yı kod ile orkestre etmeyi seçti. Elenen alternatif —
süreci **diyagramla** tanımlayan bir motor — hiç denenmedi, dolayısıyla ADR'deki
karşılaştırma ölçüme değil okumaya dayanıyor. Bu depoda her ADR'nin arkasında bir
deney var; bu ADR'nin yok.
**Aday:** Camunda ile aynı saga'nın ikinci bir uygulaması, karşılaştırma ADR'si.
**Maliyet:** 1-2 gün.

### 9. Log arama modelinin karşılaştırması — Kibana
**Boşluk:** ADR-0015 Loki'yi seçti: **yalnızca etiketleri** indeksler, ucuzdur,
bedeli sorgunun önce etiketle daraltılması. Ters indeksli tam metin araması (log
METNİNİN indekslenmesi) ile farkı **yerinde** görülmedi. Elasticsearch zaten ayakta.
**Aday:** Kibana + Alloy'dan ikinci çıkış. Loki kalır; amaç karşılaştırma.
**Maliyet:** 2 saat.

### 10. ROADMAP'te zaten duran açıklar
gRPC, contract testing, chaos testing, API sürümleme — `docs/ROADMAP.md`
"Faz sonrası" başlığında. Bunların bir kısmı bu projede tören olur diye
değerlendirilmişti; o değerlendirme geçerli, sıraya alınırsa gerekçesi yenilenmeli.

---

## Kural

**Hepsi eklenmeyecek.** Bir aracı yüzeysel kullanmak, o boşluğu kapatmaz —
üstünü örter. Altı tanesini derinlemesine kullanmak yirmisine dokunmaktan iyidir.

Her araç için bu depodaki alışkanlığa uygun bir **ADR** yazılacak: hangi problemi
çözdü, neyi eledi, bedeli ne. Ölçülmemiş bir gerekçe ADR değildir.
