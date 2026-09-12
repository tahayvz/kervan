#!/usr/bin/env bash
#
# Duman testi — KUBERNETES surumu.
#
# NEDEN AYRI BIR BETIK
# -------------------
# scripts/smoke.sh compose'a baglidir: `docker exec kervan-<servis>` cagirir,
# Keycloak'tan token ister, Prometheus/Grafana/Loki/Jaeger'i localhost'ta arar.
# Helm paketinde bunlarin HICBIRI yok — pakette yalnizca postgres, mongo, redis,
# kafka ve (acikken) elasticsearch var. O betigi kumede kosturmak, 17 kontrolun
# 11'ini daha ilk saniyede kirardi.
#
# Bu betik kumede GERCEKTEN kanitlanabilecek seyi sorar.
#
# NE KANITLAR
#   - Pod'lar yalnizca "Running" degil, HAZIR; ve yeniden baslamamislar
#   - Her servis kendi YONETIM portunda saglikli (probe'larin baktigi uc)
#   - Ag gecidi disaridan erisilebiliyor ve yonlendirme yapiyor
#   - Yetkilendirme duruyor: tokensiz siparis ucu 401
#   - ADR-0014 duruyor: metrik ucu ana kapida yok, disariya acilan Service'te
#     yalnizca is portu var
#
# NE KANITLAMAZ
#   Uctan uca siparis akisi. Pakette Schema Registry, Debezium Connect ve
#   Keycloak yok; saga burada dogrulanmaz. O, Faz 10'da compose uzerinde
#   kanitlandi. Bu betigin sorusu "yapilandirma kumede dogru mu", "is mantigi
#   dogru mu" degil.
#
# Kullanim:  scripts/smoke-k8s.sh

set -uo pipefail

GATEWAY="${KERVAN_GATEWAY:-http://localhost:8000}"
NS="${KERVAN_NAMESPACE:-default}"

PASS=0
FAIL=0

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf '  [OK]   %s\n' "$name"
    PASS=$((PASS + 1))
  else
    printf '  [HATA] %s\n' "$name"
    FAIL=$((FAIL + 1))
  fi
}

say() { printf '%s\n' "$*"; }

kc() { kubectl -n "$NS" "$@"; }

# Bir Deployment kumede VAR MI? search-service bazi kumelerde kapali
# (values-kind.yaml): ARM dizustunde Elasticsearch calistirilamiyor. Betik her
# iki kumede de kosabilsin diye kontrol, varliga gore atlaniyor.
exists() { kc get deploy "$1" >/dev/null 2>&1; }

http_ok() { # url [beklenen_kod]
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1")
  [ "$code" = "${2:-200}" ]
}

# Ilk istek icin BIRKAC DENEME hakki.
#
# Ag gecidinde rota basina devre kesici ve 2 saniyelik zaman asimi var
# (ADR-0016). Bir servisin ILK istegi JVM'i isitir: sinif yukleme, baglanti
# havuzu, Kafka istemcisi. O istek 2 saniyeyi asarsa gecit 503 doner ve
# calisan bir sistem hatali gorunur.
#
# Yalnizca 200 BEKLEYEN kontroller icin. "Tokensiz 401" gibi bir iddiada
# tekrar denemek yanlis olurdu: orada gecikme degil, KURAL sinaniyor.
http_ok_retry() { # url [deneme]
  local tries="${2:-5}"
  for _ in $(seq 1 "$tries"); do
    http_ok "$1" && return 0
    sleep 3
  done
  return 1
}

# Kumedeki TOPLAM yeniden baslatma sayisi.
restart_total() {
  kc get pods -l app.kubernetes.io/part-of=kervan \
    -o jsonpath='{range .items[*]}{.status.containerStatuses[*].restartCount}{"\n"}{end}' \
    | awk '{s+=$1} END {print s+0}'
}

# ---------------------------------------------------------------------------

SERVICES="api-gateway:9000 catalog-service:9081 order-service:9082
          inventory-service:9084 payment-service:9086 search-service:9087"

# Baslangictaki yeniden baslatma sayisi. Sonda tekrar okunup karsilastirilacak;
# gerekcesi 2. bolumde.
RESTARTS_BEFORE=$(restart_total)

say ""
say "1) Butun pod'lar HAZIR mi?"
# "Running" yetmez: readiness probe gecmeden pod trafik almaz. Dagitim
# dogrulamasinda dogru soru "ayakta mi" degil, "yayinlaniyor mu".
for pair in $SERVICES; do
  svc="${pair%%:*}"
  deploy="kervan-${svc}"
  if ! exists "$deploy"; then
    printf '  [ATLA] %s bu kumede kapali\n' "$svc"
    continue
  fi
  check "$deploy hazir" bash -c \
    "[ \"\$(kubectl -n ${NS} get deploy ${deploy} -o jsonpath='{.status.readyReplicas}')\" = \
       \"\$(kubectl -n ${NS} get deploy ${deploy} -o jsonpath='{.spec.replicas}')\" ]"
done

say ""
say "2) Bellek yetmedigi icin oldurulen konteyner var mi?"
#
# BURADA "yeniden baslatma sayisi sifir" DIYE BIR KONTROL YOK — ve bu bilincli.
#
# Ilk yazilisinda oyleydi ve dogru calisan bir kumede kirildi: order 4,
# payment 4, inventory 5 kez yeniden baslamisti. Hicbiri hata degil. Kubernetes'te
# `depends_on` yoktur; butun pod'lar ayni anda baslar, Postgres'e bagli servisler
# o hazir olana kadar cokup yeniden baslar ve sonra YAKINSAR. Bagimlilik sirasi
# burada bir dagitim degil, dayaniklilik meselesi (infra/k8s/README.md).
#
# Yani sifir beklemek, paketin KENDI tasarimini hata saymak olurdu.
#
# Onun yerine iki ayri soru soruluyor. Birincisi burada: OOMKilled VAR MI?
# Acilirken cokme `Error` ile biter, bellek siniri asilinca `OOMKilled` ile.
# Ikincisi ayrimi yapabilmek onemli: OOMKilled hicbir zaman normal degildir ve
# klasik sebebi JVM'e konteyner sinirinin soylenmemesidir (MaxRAMPercentage).
#
# Ikinci soru — "duman testi SIRASINDA yeniden baslayan oldu mu" — en sonda.
check "OOMKilled yok" bash -c \
  "! kubectl -n ${NS} get pods -l app.kubernetes.io/part-of=kervan \
       -o jsonpath='{range .items[*]}{.status.containerStatuses[*].lastState.terminated.reason}{\" \"}{end}' \
     | grep -q OOMKilled"

say ""
say "3) Servisler kendi YONETIM portunda saglikli mi?"
# Yonetim portu kume DISINA cikmaz (ADR-0014); soru kume icinden soruluyor.
for pair in $SERVICES; do
  svc="${pair%%:*}"; port="${pair##*:}"
  deploy="kervan-${svc}"
  exists "$deploy" || continue
  check "$svc saglik" bash -c \
    "kubectl -n ${NS} exec deploy/${deploy} -- \
       wget -qO- http://localhost:${port}/actuator/health | grep -q '\"status\":\"UP\"'"
done

say ""
say "4) Ag gecidi disaridan yonlendiriyor mu?"
# NodePort 30080 -> kind dugumunde 8000 (kind-cluster.yaml).
check "urun listesi (acik uc)"     http_ok_retry "${GATEWAY}/api/v1/products"
check "siparis ucu tokensiz 401"   http_ok "${GATEWAY}/api/v1/orders/yok" 401
if exists kervan-search-service; then
  check "arama (acik uc)"          http_ok_retry "${GATEWAY}/api/v1/search/products?q=test"
else
  say "  [ATLA] arama ucu — search-service bu kumede kapali"
fi

say ""
say "5) Yonetim portu disariya SIZMIYOR mu? (ADR-0014)"
check "metrik ucu ana kapida YOK"  http_ok "${GATEWAY}/actuator/prometheus" 401
# Yapinin kendisi de dogrulaniyor: disariya acilan Service'te yalnizca TEK port
# olmali. Iki port olsaydi Kubernetes ikincisine de rastgele bir dugum portu
# atardi ve yonetim ucu her dugumun IP'sinde acilirdi (Faz 9'da olculdu: 30276).
check "public Service'te tek port" bash -c \
  "[ \"\$(kubectl -n ${NS} get svc kervan-api-gateway-public \
     -o jsonpath='{.spec.ports[*].name}' | wc -w | tr -d ' ')\" = 1 ]"

say ""
say "6) Duman testi SIRASINDA yeniden baslayan oldu mu?"
# Acilis sirasindaki yakinsama yukarida (2. bolum) aciklandi: o normal. Bu
# farkli bir soru — pod HAZIR olduktan SONRA, ilk istekleri alirken coken var mi?
# Boyle bir cokme her zaman hatadir ve sayinin kendisinde degil, ARTISINDA gorunur.
RESTARTS_AFTER=$(restart_total)
check "hazir olduktan sonra cokme yok" \
  test "$RESTARTS_BEFORE" = "$RESTARTS_AFTER"
[ "$RESTARTS_BEFORE" = "$RESTARTS_AFTER" ] || \
  say "         (once: ${RESTARTS_BEFORE}, sonra: ${RESTARTS_AFTER})"

say ""
say "--------------------------------------------"
say "  basarili: ${PASS}   basarisiz: ${FAIL}"
say "--------------------------------------------"
[ "$FAIL" -eq 0 ]
