# Gözlemlenebilirlik yığını

Üç ayak, tek yerde: **iz** (trace), **metrik**, **log**.

| Ne | Adres | Karar kaydı |
|---|---|---|
| Grafana (panolar) | http://localhost:3000 | — |
| Prometheus (metrik) | http://localhost:9090 | ADR-0014 |
| Jaeger (iz) | http://localhost:16686 | ADR-0012 |
| Loki (log) | http://localhost:3100 | ADR-0015 |
| OTel Collector | 4317 / 4318 | ADR-0012 |
| Alloy (log toplayıcı) | http://localhost:12345 | ADR-0015 |

```bash
docker compose -f infra/docker/docker-compose.yml up -d \
  jaeger otel-collector prometheus grafana loki alloy
```

Grafana'da giriş ekranı yok (lokal kolaylık). Pano: **Kervan — genel bakış**.

---

## Servisleri log'ları görünecek şekilde çalıştırmak

Servisler bu makinede çalışıyor, konteynerde değil. Alloy ise konteynerde ve
**depo kökündeki `logs/` klasörünü** okuyor. Yani log dosyası oraya düşmeli.

En kolayı: servisi depo kökünden çalıştır.

```bash
mvn -pl order-service spring-boot:run
```

Maven `spring-boot:run` çalışma dizinini **modül klasörü** yapar; o durumda dosya
`order-service/logs/` altına düşer ve Alloy onu göremez. Tam yolu vererek bunu aş:

```bash
KERVAN_LOG_FILE="$PWD/logs/order-service.json" mvn -pl order-service spring-boot:run
```

Doğrulama:

```bash
ls -l logs/                                  # dosya oluştu mu
curl -s localhost:3100/loki/api/v1/label/service/values   # Loki gördü mü
```

---

## Metrikler neden bazı portlarda?

Actuator **iş portunda değil**. Her serviste ayrı bir yönetim portu var:
iş portu + 1000.

| Servis | İş | Yönetim |
|---|---|---|
| api-gateway | 8000 | 9000 |
| catalog-service | 8081 | 9081 |
| order-service | 8082 | 9082 |
| inventory-service | 8084 | 9084 |
| payment-service | 8086 | 9086 |
| search-service | 8087 | 9087 |

Gerekçe ADR-0014'te. Kısaca: ağ geçidi dışarıya açılan tek süreç ve metrik ucu
yönlendirme kimliklerini, istek hızlarını ve JVM ayrıntılarını yayıyor.

---

## Log'dan ize atlamak

Grafana → Explore → Loki → `{service="order-service"}`.

Bir satırı aç: **TraceID** bağlantısı çıkar, tıklayınca Jaeger'da o isteğin
tamamı açılır.

Bağlantıyı kuran şey, Loki veri kaynağındaki *türetilmiş alan* (derived field):
satırın içindeki `traceId` alanını sorgu anında yakalar.

**İz kimliği bilerek etiket değil.** Etiket olsaydı her istek Loki'de yeni bir
akış açardı — metrik tarafındaki kardinalite sorununun birebir aynısı. Etiketler
yalnızca `service` ve `level`; ikisinin de değer kümesi sonlu.

---

## Sık karşılaşılan durumlar

**Grafana panoları boş.** Prometheus hedeflerine bak:
http://localhost:9090/targets — servis çalışmıyorsa hedef `down` görünür.

**p95 paneli boş, diğerleri dolu.**
`management.metrics.distribution.percentiles-histogram` kapalıdır; o olmadan
`_bucket` serileri hiç oluşmaz ve `histogram_quantile` boş döner. Hata vermez.

**Outbox panelleri boş.** Debezium devredeyse normaldir: o ölçümler yalnızca
uygulama içi yayıncı açıkken üretilir (ADR-0014). Gecikme o modda Debezium'un
`MilliSecondsBehindSource` metriğinden okunur.

**Loki'de hiç log yok.** `logs/` klasörüne dosya düşüyor mu? Düşmüyorsa
`KERVAN_LOG_FILE` yukarıdaki gibi verilmemiştir.

**Konteyner açılır açılmaz çöküyor, hata "bellek/mimari" gibi görünüyor.**
Önce diske bak: `docker system df`. Docker'ın sanal diski dolduğunda `mmap`
kullanan programlar temiz bir "yer yok" hatası yerine `SIGBUS` ile ölür.
