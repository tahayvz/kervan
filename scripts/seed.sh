#!/usr/bin/env bash
#
# Sentetik veri tohumlama (Faz 10).
#
# Ne yapar:
#   1. Keycloak'tan yonetici token'i alir
#   2. Ag gecidi uzerinden N urun olusturur ve yayina alir
#   3. Her urun icin stok kaydi acar
#
# Neden ag gecidi uzerinden? Cunku amac yalnizca veri uretmek degil; yolun
# kendisini de calistirmak. Veritabanina dogrudan yazsaydik kimlik dogrulama,
# yetki, dogrulama ve olay yayini hic calismazdi -- yani tohumlama, sistemin
# calistigina dair hicbir sey kanitlamazdi.
#
# ESKIDEN BIR ISTISNA VARDI: STOK. inventory-service'in stok girisi icin ucu
# yoktu ve bu betik stogu `psql` ile DOGRUDAN o servisin veritabanina yaziyordu.
# Yani "her servis kendi verisine sahiptir" kurali (ADR-0001) tam da onu
# dogrulamasi gereken betik tarafindan deliniyordu.
#
# ADR-0019 ile kapandi: stok artik mal kabul ucundan giriyor. Betigin artik
# veritabani erisimine ihtiyaci YOK; her sey ag gecidinden gecer.
#
# Kullanim:  scripts/seed.sh [urun_sayisi]

set -euo pipefail

PRODUCT_COUNT="${1:-25}"
GATEWAY="${KERVAN_GATEWAY:-http://localhost:8000}"
KEYCLOAK="${KERVAN_KEYCLOAK:-http://localhost:8080}"
STOCK_PER_SKU="${KERVAN_SEED_STOCK:-100000}"

# Tohumlama makbuzlarinin onegi.
#
# AYNI ONEKLE IKINCI KEZ KOSULURSA STOK TEKRAR EKLENMEZ. Makbuz kimligi ayni kalir
# ve uc idempotenttir (ADR-0019) -- yani ikinci kosu hicbir sey yapmaz ama yine 200
# doner. Bu bilincli: aglar koptugunda betigi yeniden calistirmak guvenli olmali.
#
# Bedeli su: yuk testi stogu tuketttikten sonra betigi tekrar kosturmak stogu
# TAZELEMEZ. Bu yuzden asagida rapor, eklenen miktari degil KUMEDEKI GERCEK stogu
# yaziyor ve beklenenin altindaysa ne yapilacagini soyluyor.
RECEIPT_PREFIX="${KERVAN_SEED_RECEIPT_PREFIX:-seed}"

say() { printf '%s\n' "$*" >&2; }

# ---------- 1) Token ----------
say "Keycloak'tan yonetici token'i aliniyor..."
TOKEN=$(curl -sS -X POST \
  "${KEYCLOAK}/realms/kervan/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=kervan-cli \
  -d username=yonetici -d password=yonetici \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

if [ -z "${TOKEN}" ]; then
  say "HATA: token alinamadi. Keycloak ayakta mi? ${KEYCLOAK}"
  exit 1
fi

# ---------- 1b) CDC konektoru ----------
#
# SIRA ONEMLI: konektor urunlerden ONCE kaydedilmeli.
#
# Konektor `snapshot.mode=no_data` ile calisir, yani kendisi baslamadan ONCEKI
# kayitlari GORMEZ. Once tohumlayip sonra kaydedersen urunler veritabaninda
# durur ama arama indeksine hic dusmez -- ve hicbir yerde hata cikmaz. Yalnizca
# arama bos doner.
#
# (Onceki adimda basarisiz olan tam buydu.)
CONNECT="${KERVAN_CONNECT:-http://localhost:8083}"
CONNECTOR_NAME="kervan-catalog-products"

if curl -sf "${CONNECT}/connectors/${CONNECTOR_NAME}" >/dev/null 2>&1; then
  say "CDC konektoru zaten kayitli: ${CONNECTOR_NAME}"
else
  say "CDC konektoru kaydediliyor: ${CONNECTOR_NAME}"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${CONNECT}/connectors" \
    -H 'Content-Type: application/json' \
    --data-binary @"$(dirname "$0")/../infra/docker/debezium/catalog-products-connector.json")
  if [ "${CODE}" != "201" ]; then
    say "HATA: konektor kaydedilemedi (HTTP ${CODE}). Kafka Connect ayakta mi? ${CONNECT}"
    exit 1
  fi
  # Konektorun akisa gecmesi birkac saniye surer; once urun yazilirsa kacar.
  say "  konektorun akisa gecmesi bekleniyor..."
  sleep 15
fi

# ---------- 2) Urunler ----------
BRANDS=(Nike Adidas Puma Reebok NewBalance)
CATEGORIES=("ayakkabi/kosu" "ayakkabi/gunluk" "giyim/ust" "giyim/alt" "aksesuar/canta")
COLORS=(siyah beyaz mavi kirmizi gri)

say "${PRODUCT_COUNT} urun olusturuluyor..."
SKUS=()
for i in $(seq 1 "${PRODUCT_COUNT}"); do
  SKU="SKU-$(printf '%04d' "$i")"
  BRAND="${BRANDS[$(( (i - 1) % ${#BRANDS[@]} ))]}"
  CATEGORY="${CATEGORIES[$(( (i - 1) % ${#CATEGORIES[@]} ))]}"
  COLOR="${COLORS[$(( (i - 1) % ${#COLORS[@]} ))]}"
  # Fiyatlar 100.00 ile 3000.00 arasinda dagilsin; odeme simulatorunun ret
  # siniri (10000) asilmasin ki siparislerin cogu BASARIYLA tamamlansin.
  PRICE=$(( 100 + (i * 137) % 2900 ))

  BODY=$(python3 - "$SKU" "$BRAND" "$CATEGORY" "$COLOR" "$PRICE" <<'PY'
import json, sys
sku, brand, category, color, price = sys.argv[1:6]
print(json.dumps({
    "sku": sku,
    "name": f"{brand} {category.split('/')[-1]} {sku[-4:]}",
    "description": f"Sentetik test urunu ({color})",
    "brand": brand,
    "categoryPath": category,
    "price": {"amount": f"{price}.00", "currency": "TRY"},
    "attributes": {"renk": color, "beden": "42"},
}, ensure_ascii=False))
PY
)

  ID=$(curl -sS -X POST "${GATEWAY}/api/v1/products" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H 'Content-Type: application/json' \
        -d "${BODY}" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("id",""))')

  if [ -z "${ID}" ]; then
    say "  ${SKU}: olusturulamadi (zaten var olabilir), atlaniyor"
    continue
  fi

  # Urun DRAFT dogar; aramada ve siparislerde gorunmesi icin yayina alinir.
  curl -sS -o /dev/null -X POST "${GATEWAY}/api/v1/products/${ID}/activate" \
    -H "Authorization: Bearer ${TOKEN}"

  SKUS+=("${SKU}")
done
say "  ${#SKUS[@]} urun olusturuldu ve yayina alindi."

# ---------- 3) Stok ----------
#
# Ag gecidi uzerinden, mal kabul ucuyla (ADR-0019). Eskiden burada `psql` vardi;
# betik baska bir servisin veritabanina dogrudan yaziyordu.
say "Mal kabulu yapiliyor (SKU basina ${STOCK_PER_SKU})..."
STOCK_OK=0
STOCK_FAIL=0
# Kumedeki GERCEK stogun en dusugu. Cevabin govdesinden okunuyor; "ne gonderdim"
# degil "ne oldu" raporlanacak.
MIN_AVAILABLE=""
for SKU in "${SKUS[@]}"; do
  BODY=$(curl -sS -w '\n%{http_code}' -X POST \
    "${GATEWAY}/api/v1/stock/${SKU}/receipts" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"receiptId\":\"${RECEIPT_PREFIX}-${SKU}\",\"quantity\":${STOCK_PER_SKU}}")

  CODE="${BODY##*$'\n'}"
  JSON="${BODY%$'\n'*}"

  if [ "${CODE}" != "200" ]; then
    STOCK_FAIL=$((STOCK_FAIL + 1))
    say "  ${SKU}: mal kabulu basarisiz (HTTP ${CODE})"
    continue
  fi

  STOCK_OK=$((STOCK_OK + 1))
  AVAILABLE=$(printf '%s' "${JSON}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["available"])' 2>/dev/null || echo "")
  if [ -n "${AVAILABLE}" ]; then
    if [ -z "${MIN_AVAILABLE}" ] || [ "${AVAILABLE}" -lt "${MIN_AVAILABLE}" ]; then
      MIN_AVAILABLE="${AVAILABLE}"
    fi
  fi
done

if [ "${STOCK_FAIL}" -gt 0 ]; then
  say "HATA: ${STOCK_FAIL} SKU icin stok girilemedi."
  exit 1
fi

say ""
say "Tohumlama bitti."
say "  urun    : ${#SKUS[@]}"
# "Ne gonderdim" degil "kumede ne var". Ikisi ayni degil: ayni onekle ikinci kez
# kosulunca hicbir sey eklenmez ve eski betik yine "her birine 100000" diye yazardi.
if [ -n "${MIN_AVAILABLE}" ]; then
  say "  stok    : ${STOCK_OK} SKU; en dusuk satilabilir miktar ${MIN_AVAILABLE}"
  if [ "${MIN_AVAILABLE}" -lt "${STOCK_PER_SKU}" ]; then
    say ""
    say "  NOT: stok beklenenin altinda. Muhtemelen bu betik daha once kosmus ve"
    say "       araya bir yuk testi girmis. Ayni makbuz ikinci kez eklenmez."
    say "       Taze stok icin onegi degistir:"
    say "         KERVAN_SEED_RECEIPT_PREFIX=kosu2 scripts/seed.sh"
  fi
else
  say "  stok    : ${STOCK_OK} SKU (miktar okunamadi)"
fi
say ""
say "Arama indeksinin dolmasi birkac saniye surer (CDC -> Kafka -> Elasticsearch)."
say "Kontrol:  curl -s '${GATEWAY}/api/v1/search/products?q=Nike' | head -c 300"
