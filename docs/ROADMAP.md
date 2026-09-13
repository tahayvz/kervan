# Kervan — Yol Haritası (Roadmap)

Bu belge, projenin **faz faz** nasıl büyüyeceğini anlatır. Amaç, her fazın sonunda
**çalışan, test edilmiş ve dokümante edilmiş** bir artefakt bırakmaktır — büyük
kurumlarda "incremental delivery" (parça parça teslim) böyle yapılır.

Her fazın altında:
- **Neden** (bu faz hangi kurumsal problemi çözüyor / hangi yetkinliği gösteriyor)
- **Ne inşa edilecek**
- **Kazanım** (bu fazın ortaya koyduğu yetkinlik)

---

## Faz 0 — Temel & Dokümantasyon  ✅

**Neden:** Kod yazmadan önce kapsam, mimari ve kararlar netleşmeli. "Neden bu
teknoloji?" sorusunun yazılı bir cevabı yoksa, karar altı ay sonra savunulamaz.

**Ne inşa edildi:**
- Mono-repo iskeleti (Maven multi-module parent POM)
- `README`, `ARCHITECTURE`, `TECH-RADAR`, `ROADMAP`
- ADR (Architecture Decision Record) çerçevesi + ilk kararlar
- Lokal altyapı için Docker Compose (Postgres ile başlar, her fazda büyür)
- `.gitignore`, `.editorconfig`, kod stili temeli

**Kazanım:** Her mimari karar gerekçesiyle ADR olarak kayıtlı; bağımlılıklar
mono-repo + multi-module Maven ile merkezî olarak yönetiliyor.

---

## Faz 1 — Catalog Service (ilk mikroservis)  ✅

**Neden:** Event-driven'a geçmeden önce **tek bir servisi kusursuz** yapmak gerekir:
temiz katmanlı mimari, migration disiplini, sözleşme öncelikli (API-first) tasarım,
gerçek veritabanına karşı entegrasyon testi.

**Ne inşa edilecek:**
- `catalog-service`: ürün / kategori / marka yönetimi
- **MongoDB** (document / NoSQL) + Spring Data MongoDB — esnek ürün öznitelikleri (ADR-0006)
- **Mongock** (versiyonlu migration / indeks oluşturma — MongoDB'nin Flyway'i)
- REST API + **OpenAPI** (springdoc) — sözleşme dokümante
- Katmanlı mimari (web → application → domain → infrastructure)
- **Testcontainers** ile gerçek MongoDB'ye karşı entegrasyon testi
- Çok aşamalı (multi-stage) **Dockerfile** (küçük, güvenli imaj)
- Global hata yönetimi (RFC 7807 `ProblemDetail`)

**Kazanım:** Katalog MongoDB'de document olarak modellendi — ürün öznitelikleri
kategoriye göre değiştiği için ilişkisel şema EAV anti-desenine zorluyordu. Mongock
ile NoSQL migration'ları versiyonlandı, Testcontainers ile gerçek MongoDB'ye karşı
test edildi, OpenAPI sözleşmesi API-first üretildi.
> Not: İlişkisel dünya ve **Flyway** Faz 4'te gelir (Order/Payment/Inventory → Postgres),
> böylece iki migration aracını (Mongock + Flyway) da göstermiş oluruz.

---

## Faz 2 — API Gateway + Kimlik (Keycloak)  · 2a ✅ · 2b ✅

**Neden:** Kurumsal sistemlerde her servis kendi auth'unu yazmaz. Merkezî bir
**kimlik sağlayıcı** (Keycloak) ve tek giriş noktası (**Gateway**) olur.

**Ne inşa edilecek:**
- **Spring Cloud Gateway**: routing, merkezî JWT doğrulama, rate-limit köprüsü
- **Keycloak**: OAuth2 / OIDC, realm & client yapılandırması, rol tabanlı yetki
- Servislerde `resource-server` (JWT doğrulama, `@PreAuthorize`)
- Compose'a Keycloak eklenir

**Kazanım:** Kimlik doğrulama Keycloak'a devredilir; token Gateway'de doğrulanıp
claim'ler downstream servislere taşınır, servisler stateless resource-server olur.

**2b'de yapılanlar (`api-gateway`, port 8000):** Spring Cloud Gateway ile yönlendirme
(`/api/v1/products/**` → catalog, `/api/v1/orders/**` → order) ve merkezî JWT
doğrulama. **Yetki denetimi bilinçli olarak taşınmadı** — gerekçesi ADR-0007'de:
kural iki yere yazılırsa kayar, ve kayıt bazlı sahiplik ağ geçidinde zaten yapılamaz.
9 test: yönlendirmenin doğru servise gittiği, token'ın aşağı iletildiği ve kimliksiz
isteğin arka servise **hiç ulaşmadığı** doğrulanıyor.

Rate limiting bu faza alınmadı; Redis tabanlı sınırlama Faz 7'de.

---

## Faz 3 — Event-Driven Omurga (Kafka + Avro + Outbox + Debezium)  ✅

**Neden:** Bu, projenin **kalbi** ve seni ayrıştıran kısım. Senkron REST çağrıları
servisleri birbirine kilitler (coupling) ve zincirleme hataya (cascading failure)
açar. Event-driven mimari bunu çözer — ama "dual write" problemi (DB + Kafka'ya aynı
anda yazma) ele alınmazsa veri tutarsız kalır.

**Ne inşa edilecek:**
- **Apache Kafka** + **Confluent Schema Registry** — ✅ yapıldı
- **Apache Avro** ile şemalı event'ler (geriye/ileriye uyumluluk) — ✅ yapıldı
- **Transactional Outbox** deseni: event, iş verisiyle **aynı transaction**'da
  outbox tablosuna yazılır — ✅ yapıldı
- **Debezium** (CDC): değişiklikleri okuyup Kafka'ya taşır — "dual write" problemi
  kökten çözülür. **İki kaynak:** Postgres (WAL, outbox tablosu) ve MongoDB
  (change streams, Catalog) — ✅ ikisi de yapıldı. Aynı araç, iki farklı depo
  (ADR-0006). Not: Postgres tarafı outbox okur (domain olayı), Mongo tarafı
  koleksiyonu okur (verinin yansıması); ikisi kasten farklı — bkz. ARCHITECTURE §4.2
- Ortak `event-contracts` modülü (Avro şemaları tek yerde) — ✅ yapıldı

**Kazanım:** Dual-write problemi Transactional Outbox + Debezium CDC ile çözülür;
event'ler Avro + Schema Registry ile şemalanıp geriye dönük uyumluluk garanti edilir.

---

## Faz 4 — Sipariş Akışı + Saga (dağıtık tutarlılık)  ✅

**Neden:** Bir siparişte 3 servis (Order, Payment, Inventory) tutarlı olmalı ama
dağıtık transaction (2PC) ölçeklenmez. Çözüm: **Saga** (telafi edici işlemler).

**Ne inşa edilecek:**
- `order-service`, `payment-service`, `inventory-service` — hepsi **PostgreSQL + Flyway**
  (para/stok ACID ister; Faz 1'deki Mongock'un yanına Flyway'i de göstermiş oluruz)
- **Saga (orchestration)**: OrderCreated → ReserveStock → ProcessPayment →
  ConfirmOrder; herhangi bir adım başarısızsa **compensation** (StockReleased,
  PaymentRefunded, OrderCancelled)
  - Mesaj sözleşmeleri ve konu topolojisi — ✅ yapıldı (ADR-0009)
  - `inventory-service` (stok ayırma + telafi) — ✅ yapıldı
  - `payment-service` (tahsilat + iade telafisi) — ✅ yapıldı
  - Saga orchestrator (durum makinesi, telafi, idempotent adımlar) — ✅ yapıldı
- İdempotent tüketici (aynı event iki kez işlenmez) — ✅ yapıldı
- Durum makinesi (state machine) ile sipariş yaşam döngüsü — ✅ yapıldı

**Kazanım:** Dağıtık transaction yerine orchestration-based Saga kullanılır; her
adımın telafi (compensation) işlemi tanımlanır, idempotency ile tekrarlar etkisizleşir.

---

## Faz 5 — Arama (Elasticsearch) + Cache (Redis)  ✅

**Neden:** "Nike, 44 numara, siyah, 1000-2000 TL" gibi çok kriterli aramayı SQL'de
yapmak işkencedir. Ayrıca sık okunan veri her seferinde DB'ye gitmemeli.

**Ne inşa edilecek:**
- `search-service`: Kafka'daki katalog event'lerini dinleyip **Elasticsearch**'e
  indeksler (CQRS okuma modeli) — ✅ yapıldı (ADR-0010)
- Faceted search, filtreleme, sayfalama — ✅ yapıldı
- **Redis**: sık okunan katalog verisi için cache ve **rate-limit** — ✅ yapıldı
  (ADR-0011). **Dağıtık kilit YAPILMADI** ve bu bilinçli: ihtiyaç duyulan her yerde
  veritabanı kilidi zaten var (stokta `FOR UPDATE`, saga satırında kilit, outbox'ta
  `SKIP LOCKED`, idempotentlikte benzersizlik kısıtı). Redis'e kilit koymak "kimde
  kilit var" sorusuna ikinci bir cevap yaratırdı. Gerekçe ve gerekirse izlenecek
  sıra ADR-0011'de.

**Kazanım:** Arama için CQRS okuma modeli kurulur; Kafka'dan beslenen Elasticsearch
indeksiyle faceted search, Redis ile cache ve dağıtık kilit sağlanır.

---

## Faz 6 — Gözlemlenebilirlik (Observability)  ✅

**Neden:** Mikroservislerde bir hata 5 servise yayılabilir. "Nerede, kaç ms takıldı?"
sorusuna cevap veremezsen prod'da körsün. Üç ayak: **log, metrik, trace**.

**Ne inşa edilecek:**
- **OpenTelemetry** ile enstrümantasyon (trace + metrik) — ✅ yapıldı (izleme)
- **Jaeger**: dağıtık trace (istek servisler arası nasıl aktı) — ✅ yapıldı
- **Prometheus** + **Grafana**: metrik toplama + dashboard'lar (RED) — ✅ yapıldı
- **Loki**: merkezî log; trace-id ile log korelasyonu — ✅ yapıldı

**6a'da yapılanlar (izleme):** Altı servise de `micrometer-tracing-bridge-otel` +
OTLP dışa aktarıcı eklendi. Enstrümantasyon **kod içinde**; Java ajanı bilinçli
olarak kullanılmadı (ADR-0012) — gerekçe: ajan CI'da doğrulanamaz ve bu depoda
testi olmayan davranış yoktur. Span'ler doğrudan Jaeger'a değil **OTel
Collector**'a gider; uygulama tek adres bilir.

Asıl iş, izin **asenkron geçişte kopmamasıydı**. Olay Kafka'ya uygulamadan
gitmiyor: önce outbox tablosuna yazılıyor, Debezium değişiklik günlüğünden okuyup
yayınlıyor. Debezium'un ne isteği ne de iş parçacığı var, taşıyacak bağlamı
bilemez. Çözüm: `trace_parent` sütunu + `EventRouter`'ın sütunu Kafka başlığına
kopyalaması (ADR-0013). Böylece bir sipariş Jaeger'da tek zincir olarak görünüyor:
HTTP isteği → stok → ödeme → tamamlanma.

Bir tuzak testle sabitlendi: Connect'in varsayılan başlık dönüştürücüsü JSON'dur
ve değeri tırnak içinde yazar; W3C ayrıştırıcısı böyle bir başlığı sessizce atar.
`OutboxCdcIntegrationTest` başlığın **birebir eşit** olduğunu doğruluyor.

**6b'de yapılanlar (metrik):** Altı servis de `/actuator/prometheus` ucunu açıyor,
Prometheus gelip okuyor (ADR-0014). Uygulama metrik göndermiyor: gönderen taraf
olsaydı Prometheus kapalıyken "biriktir mi, at mı" sorusunu her servis kendi
çözerdi. Çeken taraf olunca "servis ayakta mı" sorusunun cevabı da bedava geliyor
(`up`).

Çerçevenin HTTP metriklerinin yanına **iş metrikleri** eklendi: outbox'ta bekleyen
kayıt sayısı ve **en eskisinin yaşı**, kenara alınmış kayıtlar, duruma göre havada
kalan saga sayısı, stok ve ödeme sonuçları. Alarm sayıya değil yaşa kurulur: kuyrukta
tek kayıt olabilir ama o kayıt iki saattir bekliyordur.

**Actuator iş portundan taşındı.** Her serviste ayrı bir yönetim portu var (iş portu
+ 1000) ve compose onu dışarı açmıyor. Ağ geçidi dışarıya açılan tek süreç; metrik ucu
yönlendirme kimliklerini ve JVM ayrıntılarını yayıyor.
`@ConditionalOnManagementPort(DIFFERENT)` emniyet kilidi: iki port birleştirilirse
actuator'ı serbest bırakan bean kaybolur ve uçlar yeniden kimlik doğrulamasına döner.

Panolar ve veri kaynağı kodda (`infra/docker/observability`). Kendi incelemem üç
gerçek hata buldu: gecikme metriği yayıncının vazgeçtiği kayıtları sayıyordu (tek
zehirli mesaj alarmı kalıcı olarak çalar hâle getiriyordu), pano veritabanından
okunan bir gauge'i kopyalar arasında topluyordu (üç kopyada değer üçe katlanırdı) ve
Grafana veri kaynağını isimle arıyordu — panolar boş açılırdı.

**6c'de yapılanlar (log):** Servisler log'u **dosyaya JSON** olarak yazıyor; konsol
insan için okunur kalıyor. Taşımayı **Grafana Alloy** yapıyor, depo Loki (ADR-0015).
Uygulama Loki'yi tanımıyor: log'un nereye gideceği bir işletme kararı, uygulama
kararı değil. Aynı gerekçe metrikte de verilmişti.

Asıl kazanç üç ayağın **tek kimlikle** birleşmesi: Grafana'daki türetilmiş alan log
satırındaki `traceId` değerini yakalayıp Jaeger'a bağlıyor. Bir log satırından o
isteğin tüm yolculuğuna tek tıkla geçiliyor.

İz kimliği **bilerek etiket değil** — etiket olsaydı her istek Loki'de yeni bir akış
açardı; metrik tarafında sebebi etiket yapmamakla önlediğimiz kardinalite sorununun
aynısı. Etiketler yalnızca `service` ve `level`.

Log alan adı iki ayrı dosya arasında sözleşme: biri Java, diğeri Grafana ayarı.
Derleyici ikisini bağlamaz, bu yüzden `StructuredLoggingTest` depodaki **gerçek**
Grafana ayarını okuyup desenini gerçek bir log satırına uyguluyor.

**Kazanım:** OpenTelemetry ile uçtan uca trace context propagation sağlanır; bir
isteğin hangi serviste kaç ms harcadığı Jaeger'da izlenir; Grafana'da RED
metrikleriyle alarm kurulur.

---

## Faz 7 — Dayanıklılık (Resilience4j)  ✅

**Neden:** Downstream servis yavaşladığında bütün sistem çökmemeli. "Bulkhead",
"circuit breaker", "timeout", "retry" olmadan bir çağrı zinciri dominoyu devirir.

**Ne inşa edildi:**
- **Resilience4j**: circuit breaker, retry (backoff), time limiter, bulkhead — ✅
- Fallback stratejileri — ✅
- Redis tabanlı rate limiting (Gateway'de) — ✅ Faz 5'te yapılmıştı
- Gerçekten devreye girdiğini gösteren testler — ✅

**Nereye konuldu, nereye konmadı (ADR-0016).** Klasik cevap "her çağrıya devre
kesici koy" olurdu; burada yanlış olurdu. Çağrıların çoğu senkron değil ve saga
Kafka üzerinden yürüyor — orada zaten yeniden deneme + ölü mektup var (ADR-0009).
İkinci bir düzenek, "bu mesaj neden işlenmedi" sorusuna iki ayrı cevap üretirdi.
Aynı gerekçeyle dağıtık kilit de eklenmemişti (ADR-0011).

Kesici yalnızca **senkron ve dış** iki sınıra kondu:

1. **Ağ geçidi → arka servisler.** Rota başına AYRI kesici (tek ortak kesici olsaydı
   katalog arızası sipariş trafiğini de keserdi), 2 saniyelik zaman aşımı ve 503 +
   `Retry-After` dönen bir geri düşüş. Hiçbir rota uydurma veri dönmez: "siparişin
   yok" cevabı, "şu an bakamıyorum" ile aynı şey değildir.
2. **payment-service → ödeme sağlayıcısı.** Zincir `Bulkhead(CircuitBreaker(Retry))`
   sırasıyla kuruldu ve sıra testle sabitlendi.

**Zaman aşımı olmadan devre kesici işe yaramaz.** Kesici başarısızlık sayar; askıda
kalan bir çağrı başarısız değildir, hâlâ beklenmektedir. Üretimde asıl sık görülen
arıza da çökme değil yavaşlamadır.

**Para tarafında iki ayrım.** Reddedilme başarısızlık değildir — sağlayıcı çalışıyor
ve "hayır" diyor; kesici bunu saysaydı meşru bir ret dalgası çalışan bir sağlayıcıya
giden bütün ödemeleri keserdi. Ve her "ulaşamadım" tekrar denenmez: istek gidip cevap
gelmediyse tahsilat yapılmış olabilir, körlemesine tekrar denemek ikinci kez para
çekmektir. Karar hatanın tipinden değil içeriğinden veriliyor.

**Kazanım:** Çöken servise yük binmeye devam etmiyor, istemci hızlı ve anlaşılır bir
cevap alıyor, kesici durumu Faz 6 panosunda görünüyor.

---

## Faz 8 — CI/CD (GitHub Actions)  ✅

**Neden:** "Bende çalışıyordu" bir mühendislik cevabı değildir. Her commit otomatik
derlenmeli, test edilmeli, taranmalı ve imaj üretmeli — **ve o imajın açıldığı
gösterilmeli.**

**Ne inşa edildi (8a — CI):**
- **GitHub Actions** pipeline: build + birim + Testcontainers entegrasyon testleri
- CodeQL taraması
- Matris ile altı imajın paralel derlenmesi, Maven önbelleği, PR kapısı

**Ne inşa edildi (8b — CD doğrulaması, ADR-0018):**
- Her commit'te **atılacak bir kind kümesi** kurulur, Helm paketi **gerçekten
  uygulanır**, pod'ların hazır olması beklenir, `scripts/smoke-k8s.sh` koşar ve
  küme silinir.
- İmajlar matris işinden **iş çıktısı (artifact)** olarak taşınır. Tek makinede
  yeniden derlemek ~30 dakika sürerdi (ölçüldü: 8+7+6+5+2+2 dk); bu yol ~2 dakika
  ekliyor ve paralelliği koruyor.
- **CI'da hiçbir şey kapatılmaz:** runner amd64 olduğu için Elasticsearch ve
  `search-service` de açılıyor — yani altı imajın **altısı da** çalıştırılarak
  doğrulanıyor. Yerel kümede bu mümkün değil (ARM, günlük B20).
- Ucuz ön kapı: `helm lint` + üç değer dosyasıyla `helm template`.
- İş kırılırsa pod listesi, olaylar, `describe` ve bütün logların (`--previous`
  dâhil) çıktı olarak yüklenmesi.

**Neden gerçek bir ortama dağıtım yok:** Dağıtılacak yer yok; elde tek sunucu var ve
o başka bir üretim sitesini çalıştırıyor. GHCR'a imaj gönderme de eklenmedi —
değerli ama *farklı* bir boşluğu kapatır, çalıştığını kanıtlamaz (ADR-0018).

**Kazanım:** Faz 9 ve 10'da çıkan on yedi hatanın çoğunun ortak sebebi tek cümleydi:
*CI imajı derliyor ama çalıştırmıyor.* O boşluk kapandı. Bedeli: CI süresi ~13
dakikadan ~24 dakikaya çıktı.

**Neyi doğrulamaz:** Uçtan uca sipariş akışını. Pakette Schema Registry, Debezium
Connect ve Keycloak yok; saga burada kanıtlanmaz — o Faz 10'da compose üzerinde
kanıtlandı.

---

## Faz 9 — Kubernetes + Helm  ✅

**Neden:** Compose lokal içindir; kurumsal deploy Kubernetes'tir. Bir lead
"Spring Boot'u K8s'e nasıl deploy edersin?" sorusuna uygulamalı cevap verebilmeli.

**Ne inşa edildi:**
- **Tek Helm paketi**, altı servis aynı şablondan (ADR-0017). Servis başına ayrı
  paket, altı dosyada aynı hatayı altı kez düzeltmek olurdu.
- Liveness/readiness/startup probe'ları — **yönetim portunda** (Faz 6b'nin karşılığı)
- Resource requests + bellek limiti; **CPU limiti bilerek yok**
- Düzgün kapanma (graceful shutdown) + açıkça yazılmış güncelleme stratejisi
- **kind** ile gerçekten uygulandı ve çalıştırıldı
- HPA yapılmadı: tek düğümlü bir denemede ölçeklenecek bir şey yok; metrics-server
  ve gerçek yük olmadan HPA yazmak, çalıştığı hiç görülmeyen yapılandırma olurdu

**Ölçüldü:** 9 pod'un 9'u hazır; ağ geçidi NodePort'tan cevap veriyor; servisler
birbirini DNS ile buluyor; **güncelleme sırasında 250 isteğin 250'si 200 döndü**
(kesintisiz güncelleme = readiness probe + düzgün kapanma + `maxUnavailable: 0`).

**Compose'dan gelirken çıkan üç hata** (günlük A33, B35):

| Hata | Sebep |
|---|---|
| Üç servis açılışta çöktü | Kubernetes'te `depends_on` yok; hepsi aynı anda başlar. Yeniden başlatarak **yakınsadı** — burada sıra bir *dağıtım* değil *dayanıklılık* meselesi |
| Kafka hiç hazır olmadı | KRaft controller adresi olarak **servis adı** yazılmıştı; Service yalnızca *hazır* pod'lara yönlendirdiği için kilitlendi. Compose'da aynı ad doğrudan konteynere çözülüyordu |
| Kafka açılmadı | `CLUSTER_ID` serbest metin değil: base64url ile tam 16 bayt olmalı. Compose'daki değer tesadüfen 22 karakterdi |

**Kendi incelemem sekiz bulgu çıkardı**; ikisi gerçek hataydı:

- **Yönetim portu dışarı açılmıştı.** Kubernetes, `NodePort` tipindeki bir
  Service'in **her** portuna düğüm portu atar — pinlenmemiş olana rastgele bir
  tane. Ölçüldü: yönetim portu 30276 almıştı, yani Faz 6b'de verilen ve ADR-0014'e
  yazılan karar sessizce bozulmuştu. Çözüm: dışarı açılan Service ayrı ve
  **yalnızca iş portunu** yayınlıyor.
- **Belge gerçeği anlatmıyordu.** Yorumlar "veritabanı erişilemezken trafik
  alınmaz" diyordu; Spring'in varsayılan readiness grubu ise yalnızca
  `readinessState` içeriyor. Bağımlılık eklemek yerine **belge düzeltildi**:
  eklenseydi bir veritabanı sarsıntısı bütün pod'ları aynı anda trafikten
  çekerdi — Faz 5'teki Redis kararının aynısı.

Ayrıca: `preStop` beklemesi (endpoint yayılımı SIGTERM ile yarışıyor),
`securityContext` (runAsNonRoot + sayısal UID), portların tek kaynaktan verilmesi,
NetworkPolicy'nin **yokluğunun** belgelenmesi.

**Kapsam sınırı, açıkça:** bu faz **yapılandırmayı ve orkestrasyonu** doğrular,
uçtan uca veri akışını değil. Kümede Schema Registry ve Debezium Connect yok
(ağır, emülasyon gerektiriyor); saga'nın çalıştığı Faz 10'da compose üzerinde
kanıtlandı. `search-service` yerel kümede kapalı — Elasticsearch'e bağlanamazsa
hiç açılmıyor ve ARM imajı bu makinede çöküyor; Kubernetes'te imaj başına platform
seçimi yok.

**Kazanım:** Servisler Helm chart'larıyla parametrize edilir; readiness/liveness
probe, HPA ve resource limit'leriyle K8s'e deploy edilir.

---

## Faz 10 — Sentetik yük ve gözlem (projenin kapanışı)  ✅

**Neden:** Bu proje bir ürün değil; teknolojileri ve çözdükleri problemleri deneyimlemek
için var. Bir teknolojiyi "bağladım" demek ile "anladım" demek arasındaki fark, onu
**yük altında görmüş olmaktır**. Boş bir sistemde her şey çalışır.

Ayrıca buraya kadar kurulan her şeyin — outbox, CDC, saga, telafi — gerçek değeri ancak
sıkıştırıldığında görünür: kuyruk birikince, tüketici geride kalınca, bir servis
yavaşlayınca.

**Ne inşa edilecek:**
- **Sentetik veri**: sahte müşteriler, ürünler ve stok. Tek komutla yüklenebilen,
  tekrar üretilebilir bir veri seti (rastgele değil, tohumlanmış — aynı veri her
  seferinde aynı olsun ki karşılaştırma anlamlı olsun).
- **Yük üretimi**: k6 ya da Gatling ile sipariş akışına sürekli yük. Hem mutlu yol hem
  başarısızlık yolu (stok yetmeyen, ödeme reddedilen siparişler) belli oranlarda.
- **İzleme**: Faz 6'da kurulan panolarla sistemi yük altında seyretmek —
  - outbox tablosunun boyu ve temizliğin yetişip yetişmediği
  - Debezium replication slot gecikmesi
  - saga durum dağılımı ve sıkışıp kalmış saga sayısı
  - ölü mektup konularının doluluğu
  - uçtan uca gecikme (sipariş → onay)
- **Deney defteri**: "şunu kırdık, şu oldu" notları. Bir servisi durdurmak, Kafka'yı
  kesmek, veritabanını yavaşlatmak.

**Yapılanlar:** Beş servis daha compose'a alındı; sistem ilk kez **tamamı bir arada**
çalıştı. `scripts/seed.sh` ürünleri **ağ geçidi üzerinden** oluşturur (doğrudan
veritabanına yazsaydık kimlik doğrulama, yetki ve olay yayını hiç çalışmazdı).
`scripts/smoke.sh` yığının gerçekten açıldığını 17 kontrolle doğrular.
Yük `infra/docker/load/order-flow.js` ile k6 üzerinden.

**Ölçülenler** (60 sn, 10 gezinen kullanıcı + sn'de 5 sipariş): 300 sipariş, hepsi
başarılı; 1094 istek, hata yok; p95 **26.9 ms**; 302 saga `COMPLETED`, outbox
kuyruğu 0'a indi. **Bu sayılar kapasite değildir** — yük üreteci ile sistem aynı
makinede, aynı CPU'yu paylaşıyor.

**Asıl çıktı tek bir iz.** Müşteri **17 ms**'de cevap alıyor; saga arkada **1.7
saniye**de tamamlanıyor (gateway → order → inventory → payment → order). 300 izin
295'i çok servisli: izi outbox sütununda taşıma düzeneği (ADR-0013) yük altında
çalışıyor.

Beklenmeyen bir gözlem: **tekrar teslimat gerçekten oldu** (servisler yeniden
derlenirken tüketiciler bazı mesajları ikinci kez işledi) ve sistem bunu sessizce
yuttu — 302 sipariş, 302 ödeme, çift çekim yok. Faz 4'teki idempotentlik ilk kez
gerçekten devreye girdi. Yan etkisi: o izin süresi 938 saniye görünüyor, yani
**tekrar teslimat iz süresini anlamsız kılıyor.**

**Bu fazın asıl bulduğu şey dokuz hata.** Hiçbirini var olan 291 test
yakalayamamıştı; hepsinin ortak dersi aynı: *bir şeyi derlemek, onun çalıştığını
kanıtlamaz.* Ayrıntı: GELISTIRME-GUNLUGU B32–B34.

| # | Hata |
|---|---|
| 1 | Keycloak'ın ARM imajı çöküyor (B20'nin üçüncü görülüşü) |
| 2 | Realm JSON'undaki `_comment` içe aktarımı reddettiriyor — realm hiç yüklenmemiş |
| 3 | Kullanıcı profili eksik → token alınamıyor |
| 4 | `api-gateway`'de repackage eklentisi yok → jar çalıştırılabilir değil |
| 5 | Kafka adresi 9092 → ağ içinden `localhost`'a yönleniyor (29092 olmalı) |
| 6 | inventory/payment web uygulaması değil → metrikleri hiç yayınlanmıyor |
| 7 | Debezium kalp atışı STRUCT, `ByteArrayConverter` bayt bekliyor → görev ölüyor |
| 8 | `K6_` öneki k6'nın kendi öneki, senaryoları eziyor |
| 9 | Konektör kaydı tohumlamadan **sonra** yapılınca veri indekse hiç düşmüyor |

**Kazanım:** Kurulan her düzeneğin gerçekten çalıştığı ölçülmüş oldu. Bu fazın
çıktısı kod değil, **sayılar ve öğrenilenlerdir.**

**Açık kalan iş — KAPANDI (ADR-0019).** `inventory-service`'in stok **girişi** için
bir ucu yoktu; stok yalnızca düşürülebiliyordu ve tohumlama betiği bunu `psql` ile
geçiyordu — yani başka bir servisin veritabanına dışarıdan yazıyordu. Artık mal kabul
ucu var (`POST /api/v1/stock/{sku}/receipts`), makbuz kimliğiyle idempotent, yalnızca
`ADMIN`. `seed.sh` veritabanına hiç dokunmuyor.

Yeni açık iş: stok **düzeltmesi** (hasarlı mal, sayım farkı) hâlâ yok. Bilinçli olarak
ertelendi — "kim, hangi gerekçeyle düzeltebilir ve nasıl denetlenir" ayrı bir sorudur.

**Üç servisin birlikte çalıştığı test — KAPANDI (ADR-0020).** `saga-e2e-tests` modülü
order, inventory ve payment'ı aynı JVM'de, gerçek PostgreSQL ve gerçek Kafka üzerinde
ayağa kaldırıp saga'nın üç yolunu yürütüyor. CDC taşıması teste özel bir köprüyle
yapılıyor; gerekçesi ve ödünü ADR'de. Mutasyonla doğrulandı.

---

## Faz sonrası (opsiyonel ileri seviye)

- **Contract testing** (Spring Cloud Contract) — servisler arası sözleşme garantisi
- **Transactional Outbox → Sink** ile analytics pipeline
- **Chaos engineering** (istek/servis öldürme deneyleri)
- **API versioning** stratejisi
- **Multi-tenant** yaklaşımı
- **gRPC** (servis-içi yüksek performanslı senkron çağrılar)

Bu maddeler CV'yi "senior"dan "staff/lead" seviyesine taşır.
