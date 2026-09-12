#!/usr/bin/env bash
#
# Duman testi (smoke test): yigin GERCEKTEN acildi mi?
#
# NEDEN VAR
# ---------
# Faz 10'da sistem ilk kez butunuyle calistirildiginda DORT gizli hata cikti ve
# hicbirini var olan testler yakalayamamisti:
#
#   1. Keycloak'in ARM imajindaki JVM cokuyor
#   2. Realm JSON'unda "_comment" alani ice aktarimi reddettiriyor
#   3. Kullanicilarin profili eksik oldugu icin token alinamiyor
#   4. api-gateway'de spring-boot-maven-plugin YOK; jar calistirilabilir degil
#      ("no main manifest attribute")
#
# Dordu de ayni aileden: IMAJI DERLEMEK, ONUN ACILDIGINI KANITLAMAZ.
# CI yesildi cunku CI imaji derliyordu; calistirmiyordu.
#
# Bu betik o bosluğu kapatir. Sadece "ayakta mi" degil, "is yapiyor mu" sorar.
#
# Kullanim:  scripts/smoke.sh

set -uo pipefail

GATEWAY="${KERVAN_GATEWAY:-http://localhost:8000}"
KEYCLOAK="${KERVAN_KEYCLOAK:-http://localhost:8080}"

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

http_ok() { # url [beklenen_kod]
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1")
  [ "$code" = "${2:-200}" ]
}

say() { printf '%s\n' "$*"; }

say ""
say "1) Servisler kendi yonetim portlarinda saglikli mi?"
# Yonetim portu disariya ACILMAZ; konteyner agindan soruluyor.
for pair in "api-gateway:9000" "catalog-service:9081" "order-service:9082" \
            "inventory-service:9084" "payment-service:9086" "search-service:9087"; do
  svc="${pair%%:*}"; port="${pair##*:}"
  check "$svc saglik" docker exec "kervan-${svc}" \
    sh -c "wget -qO- http://localhost:${port}/actuator/health | grep -q '\"status\":\"UP\"'"
done

say ""
say "2) Kimlik saglayici token veriyor mu?"
# Realm ice aktarilmadiysa ya da kullanici profili eksikse burasi kirilir.
for user in musteri yonetici; do
  check "$user icin token" bash -c \
    "curl -s --max-time 10 -X POST '${KEYCLOAK}/realms/kervan/protocol/openid-connect/token' \
       -d grant_type=password -d client_id=kervan-cli \
       -d username=${user} -d password=${user} | grep -q access_token"
done

say ""
say "3) Ag gecidi yonlendiriyor mu?"
check "urun listesi (acik uc)"        http_ok "${GATEWAY}/api/v1/products"
check "arama (acik uc)"               http_ok "${GATEWAY}/api/v1/search/products?q=test"
check "siparis ucu tokensiz 401"      http_ok "${GATEWAY}/api/v1/orders/yok" 401
# Metrik ucu DISARIYA acik olmamali (ADR-0014). 404 bekleniyor: bu portta
# actuator diye bir sey yok.
check "metrik ucu ana kapida YOK"     http_ok "${GATEWAY}/actuator/prometheus" 401

say ""
say "4) Gozlemlenebilirlik ayakta mi?"
check "Prometheus"  http_ok "http://localhost:9090/-/ready"
check "Grafana"     http_ok "http://localhost:3000/api/health"
# Loki acilistan sonra kisa bir sure "ingester not ready" der; bu gecicidir.
check "Loki"        bash -c 'for i in $(seq 1 20); do curl -sf --max-time 5 http://localhost:3100/ready >/dev/null && exit 0; sleep 3; done; exit 1' 
check "Jaeger"      http_ok "http://localhost:16686/"

say ""
say "5) Prometheus butun hedefleri kaziyabiliyor mu?"
check "hedeflerin hepsi up" bash -c \
  "curl -s --max-time 10 'http://localhost:9090/api/v1/query?query=up' \
     | python3 -c 'import json,sys; r=json.load(sys.stdin)[\"data\"][\"result\"]; \
                   down=[x[\"metric\"].get(\"job\") for x in r if x[\"value\"][1]!=\"1\"]; \
                   sys.exit(1 if down or not r else 0)'"

say ""
say "--------------------------------------------"
say "  basarili: ${PASS}   basarisiz: ${FAIL}"
say "--------------------------------------------"
[ "$FAIL" -eq 0 ]
