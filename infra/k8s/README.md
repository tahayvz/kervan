# Kubernetes (Faz 9) ve dağıtım doğrulaması (Faz 8)

Altı servis tek bir Helm paketiyle kuruluyor. Yerel doğrulama **kind** ile:
kümeyi Docker konteyneri olarak kurar, iş bitince siler.

## Çalıştırma

```bash
kind create cluster --config infra/k8s/kind-cluster.yaml

# İmajları kümeye elden yükle (dışarıdan çekilmiyorlar)
for s in api-gateway catalog-service order-service inventory-service payment-service search-service; do
  docker tag "kervan-${s}:latest" "kervan/${s}:local"
  kind load docker-image "kervan/${s}:local" --name kervan
done

helm install kervan infra/k8s/helm/kervan -f infra/k8s/helm/kervan/values-kind.yaml
kubectl get pods -w
```

Ağ geçidi: `curl http://localhost:8000/api/v1/products`

Doğrulama: `scripts/smoke-k8s.sh`

Silme: `kind delete cluster --name kervan`

## Aynısı CI'da da koşuyor (Faz 8, ADR-0018)

Yukarıdaki adımların hepsi her commit'te otomatik yapılıyor: küme kurulur, imajlar
yüklenir, paket uygulanır, `scripts/smoke-k8s.sh` koşar, küme silinir. Amaç tek bir
boşluğu kapatmak — **CI imajı derliyordu ama çalıştırmıyordu**, ve Faz 9 ile 10'da
çıkan hataların çoğu tam oradan geliyordu.

**CI'daki tek fark:** orada hiçbir şey kapatılmıyor. Runner amd64 olduğu için
Elasticsearch ve `search-service` de açılıyor (`values-ci.yaml`), yani altı imajın
altısı da çalıştırılarak doğrulanıyor. Bu dizüstünde mümkün değil.

**`smoke.sh` ile `smoke-k8s.sh` neden ayrı:** İlki compose'a bağlı — `docker exec`
çağırıyor, Keycloak'tan token istiyor, Prometheus/Grafana/Loki/Jaeger'ı `localhost`'ta
arıyor. Bu pakette bunların hiçbiri yok; o betiğin 17 kontrolünün 11'i daha ilk
saniyede kırılırdı.

**"Yeniden başlatma sayısı sıfır olmalı" bir kontrol DEĞİL.** İlk yazılışında öyleydi
ve düzgün çalışan bir kümede kırıldı: order 4, payment 4, inventory 5 kez yeniden
başlamıştı. Hiçbiri hata değil — aşağıdaki birinci maddenin doğrudan sonucu.
Betik onun yerine iki ayrı soru soruyor: `OOMKilled` var mı (açılışta çökme `Error`
ile biter, bellek sınırı aşımı `OOMKilled` ile), ve pod hazır olduktan **sonra**
çöken var mı.

## Bu faz neyi doğruluyor, neyi doğrulamıyor

**Doğruluyor:** imajlar kümede çalışıyor mu; servisler birbirini DNS ile buluyor
mu; problar doğru uçları gösteriyor mu; kaynak sınırları ve düzgün kapanma
çalışıyor mu; güncelleme kesintisiz mi (**ölçüldü: 250 isteğin 250'si 200**).

**Doğrulamıyor:** uçtan uca veri akışı. Kümede Schema Registry ve Debezium
Connect yok — ikisi de ağır ve bu makinede emülasyon gerektiriyor. Saga'nın
gerçekten çalıştığı Faz 10'da compose üzerinde kanıtlandı.

`search-service` **yerel** kümede kapalı: Elasticsearch'e bağlanamazsa hiç
açılmıyor ve Elasticsearch'ün ARM imajı bu makinede çöküyor. Kubernetes'te imaj
başına platform seçimi yok, kind düğümünün içinde de emülasyon yok. Bu bir makine
kısıtı, kod kısıtı değil — nitekim CI'da (amd64) `values-ci.yaml` hiçbir şey
kapatmıyor ve servis normal kalkıyor.

## Compose'dan Kubernetes'e geçerken değişen üç şey

**1. `depends_on` yok.** Bütün pod'lar aynı anda başlar. Postgres hazır olmadan
açılan servis çöker — ve Kubernetes onu yeniden başlatır, yakınsar. Bağımlılık
sırası burada bir *dağıtım* değil, *dayanıklılık* meselesi.

**2. Service yalnızca HAZIR pod'lara yönlendirir.** Kafka'nın KRaft controller
adresi olarak servis adını yazınca kilitlendi: Kafka hazır olmak için kendine
bağlanmak istiyor, Service ise onu henüz yayınlamıyor. Compose'da aynı ad
doğrudan konteynere çözüldüğü için sorun yoktu. Çözüm: `localhost`.

**3. Sağlık tek uç değil, iki uç.** `liveness` "yeniden başlat", `readiness`
"trafiği kes" demek. Veritabanı kesintisinde pod'u yeniden başlatmak sorunu
çözmez; yalnızca yeni bir sorun ekler.

> **Readiness bağımlılıkları İÇERMEZ.** Spring'in varsayılan readiness grubu
> yalnızca `readinessState`'tir: veritabanı erişilemezken bile uç `UP` döner.
> Bu bilinçli — bağımlılık eklenseydi bir veritabanı sarsıntısı bütün pod'ları
> aynı anda trafikten çekerdi. Kısmen bozuk bir servis, hiç olmayandan iyidir.

## Tasarım notları

- **Problar yönetim portunda.** Actuator'ı ayrı porta taşımak Faz 6b'de güvenlik
  kararıydı (ADR-0014); burada ayrıca prob trafiğini iş trafiğinin hız sınırı ve
  kimlik doğrulamasının dışında tutuyor.
- **CPU limiti yok, bellek limiti var.** CPU sıkıştırılabilir: limit koymak boş
  CPU varken bile uygulamayı bekletir (throttling) ve JVM'de bu uzun duraklamalar
  olarak görünür. Bellek sıkıştırılamaz, onun limiti şart.
- **`MaxRAMPercentage`.** Bellek limiti verip bunu vermemek klasik hatadır: JVM
  limitten habersiz kalır, heap'i büyütür ve konteyner `OOMKilled` olur.
- **ConfigMap/Secret özeti Deployment'a yazılıyor.** Aksi hâlde ayar değişse bile
  Deployment değişmediği için pod'lara dokunulmaz ve uygulama eski değerle
  çalışmaya devam eder — hiçbir yerde hata çıkmadan.
- **Yönetim portu ayrı Service'te.** Dışarı açılan Service yalnızca iş portunu
  yayınlar. İkisi tek NodePort Service'inde olsaydı Kubernetes yönetim portuna da
  rastgele bir düğüm portu atardı (ölçüldü: 30276) — ADR-0014'ün yasakladığı şey.
- **`preStop` beklemesi var.** Endpoint kaldırma kube-proxy'lere asenkron yayılır;
  o pencerede gelen istek kapanmakta olan pod'a düşer. Düzgün kapanma **başlamış**
  isteği bitirir, bu bekleme ise **henüz başlamamış** isteği karşılar.
- **NetworkPolicy YOK ve bu bir eksik.** Compose'da "port dışarı açılmıyor" gerçek
  bir koruma idi. Kubernetes'te varsayılan ağ her şeye açıktır: kümedeki herhangi
  bir pod, herhangi bir servisin `/actuator/prometheus` ucuna erişebilir. Gerçek
  kurulumda yönetim portunu yalnızca izleme ad alanına açan bir policy gerekir.
  Burada yazılmadı çünkü kind'in varsayılan CNI'ı policy uygulamıyor — yazmak
  çalıştığı sanılan ama çalışmayan bir koruma bırakırdı.
- **Veritabanı ve Kafka üretimde bu pakette olmaz.** Uygulamayla aynı pakette
  olsalardı uygulama sürümü veritabanı sürümüne bağlanırdı.
