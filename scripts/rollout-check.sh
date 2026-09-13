#!/usr/bin/env bash
#
# KESINTISIZ GUNCELLEME KONTROLU
#
# NEDEN VAR
# ---------
# README, infra/k8s/README.md ve yol haritasi ayni iddiayi tasiyor:
#
#     "guncelleme sirasinda 250 istegin 250'si 200 dondu"
#
# Bu Faz 9'da ELLE olculmus, bir kereye mahsus bir sayiydi. Arkasinda hicbir
# koruma yoktu: `maxUnavailable: 0` kaldirilsa, `preStop` beklemesi silinse ya da
# readiness probe'u is portuna tasinsa hicbir test kirilmazdi. Iddia belgede
# kalir, gercek degisirdi.
#
# Bu betik o iddiayi HER KOSUDA yeniden olcuyor.
#
# NE YAPAR
# --------
# 1. Arka planda ag gecidine surekli istek atar
# 2. Ayni anda `kubectl rollout restart` ile pod'lari degistirir
# 3. Guncelleme bitene kadar bekler
# 4. Istekleri durdurup sonucu sayar: 200 OLMAYAN TEK BIR CEVAP BILE HATADIR
#
# NEYI KORUR
#   maxUnavailable: 0   -> eski pod, yenisi HAZIR olmadan silinmez
#   readinessProbe      -> "hazir" gercekten hazir demek
#   preStop beklemesi   -> endpoint kaldirma SIGTERM ile yarisir; o pencerede
#                          gelen istek kapanan pod'a duser
#   duzgun kapanma      -> BASLAMIS istek yarida kesilmez
#
# Dordunden biri giderse bu betik kirilir.
#
# Kullanim:  scripts/rollout-check.sh [deployment-adi]

set -uo pipefail

DEPLOYMENT="${1:-kervan-api-gateway}"
NS="${KERVAN_NAMESPACE:-default}"
URL="${KERVAN_GATEWAY:-http://localhost:8000}/api/v1/products"
# Istek araligi. Cok sik atmak dugumu yorar, cok seyrek atmak kesinti penceresini
# kacirir; 50ms bir guncelleme boyunca birkac yuz istek eder.
INTERVAL="${KERVAN_ROLLOUT_INTERVAL:-0.05}"

# Bu sureden UZUN SUREN bir istek de kesinti sayilir.
#
# NEDEN SADECE DURUM KODU YETMIYOR: hazir endpoint kalmadiginda kube-proxy paketleri
# REDDETMEZ, DUSURUR. TCP el sikismasi yeniden denenir ve endpoint geri geldiginde
# baglanti kurulur -- yani istek basarisiz olmaz, ASILIR. Olculdu: koruma kaldirilinca
# 3.6 saniye boyunca hazir endpoint yoktu ve buna ragmen her cevap 200 dondu.
#
# Kullanici acisindan 3.6 saniye bekleyen bir istek kesintidir. "Hepsi 200" diyen bir
# olcum bunu gizler.
#
# 2 saniye keyfi degil: ag gecidinin arka servislere verdigi zaman asimi da 2 saniye
# (ADR-0016). Normal cevap ~20ms; 2 saniyeyi asan bir istek bu sistemde zaten
# anormaldir.
SLOW_LIMIT="${KERVAN_ROLLOUT_SLOW_LIMIT:-2.0}"

say() { printf '%s\n' "$*"; }

# Bir deployment'in su anki pod adlari. Sonlanmakta olanlar da listede gorunur --
# ve bu kasitli: "eski pod gitti" demek icin gercekten gitmis olmasi gerekir.
pods_of() {
  kubectl -n "$NS" get pods \
    -l "app.kubernetes.io/name=${1#kervan-}" \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{end}' 2>/dev/null
}

RESULT_FILE="$(mktemp)"
STOP_FILE="$(mktemp)"
rm -f "$STOP_FILE"

cleanup() {
  rm -f "$RESULT_FILE" "$STOP_FILE"
}
trap cleanup EXIT

say ""
say "Guncelleme sirasinda kesinti var mi? (${DEPLOYMENT})"
say "  hedef: ${URL}"

# --- 0) Pod'lari BULABILIYOR MUYUZ? ---
#
# Bu kontrol, olcumun kendisinden once geliyor ve sebebi su: asagidaki bekleme
# dongusu "eski pod adlarinin hicbiri kalmadi" kosuluna dayaniyor. Liste BOS
# gelirse o kosul ilk turda saglanir, dongu aninda kirilir ve geriye yalnizca
# on yuk + kuyruk kalir (~40 istek) -- bu da TOTAL esigini gecer.
#
# Yani betik hic guncelleme gormeden "kesintisiz" derdi. Ayni betik bu hatayi bir
# kez zaten yapti (bitis `kubectl rollout status` ile tanimlanmisti ve o, eski pod
# hala ayaktayken donuyordu). Sessizce hicbir sey dogrulamayan bir dogrulama,
# hic dogrulama yapmamaktan kotudur: yesil isaret yanlis guven verir.
OLD_PODS=$(pods_of "$DEPLOYMENT")
if [ -z "$(printf '%s' "$OLD_PODS" | tr -d '[:space:]')" ]; then
  say ""
  say "  [HATA] '${DEPLOYMENT}' icin pod bulunamadi."
  say "         Etiket: app.kubernetes.io/name=${DEPLOYMENT#kervan-}"
  say "         Pod listesi olmadan 'guncelleme bitti' kosulu anlamsiz olur ve"
  say "         betik hicbir sey olcmeden yesil donerdi."
  exit 1
fi

# --- 1) Yuk uretici, arka planda ---
(
  while [ ! -f "$STOP_FILE" ]; do
    # --max-time: asili kalan bir cagri sonsuza kadar beklemesin; zaman asimi
    # da bir kesintidir ve oyle sayilmali.
    # KOD VE SURE birlikte kaydediliyor. Yalnizca kod yetmez -- gerekcesi asagida,
    # SLOW_LIMIT'in yaninda.
    line=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 10 "$URL" 2>/dev/null || echo "000 99")
    printf '%s\n' "$line" >> "$RESULT_FILE"
    sleep "$INTERVAL"
  done
) &
LOAD_PID=$!

# Yuk gercekten baslasin; aksi halde guncelleme, ilk istekten once bitebilir.
sleep 2

# --- 2) Guncellemeyi tetikle ---
#
# Bitis, yukarida kaydedilen pod adlarinin YOK OLMASIYLA tanimli.
say "  pod'lar yeniden olusturuluyor..."
kubectl -n "$NS" rollout restart "deployment/${DEPLOYMENT}" >/dev/null

# --- 3) Bitmesini bekle ---
#
# `kubectl rollout status` BURADA YETMEZ ve ilk yazilisinda kullanilmisti.
# Olculdu: 4 saniyede dondu, ESKI POD HALA AYAKTAYDI (Terminating). Yani guncellemenin
# EN RISKLI ani -- eski pod'un endpoint'i kaldirilirken gelen istekler -- olcum
# bittikten SONRA yasaniyordu. Betik yesil veriyordu cunku yanlis anı olcuyordu.
#
# Dogru bitis tanimi: eski pod adlarinin HICBIRI kalmamis VE istenen sayida pod
# hazir. Sonlanmakta olan bir pod hala listede gorunur; "gitti" demek icin
# gercekten gitmesi gerekir.
DEADLINE=$(( $(date +%s) + 300 ))
while :; do
  current=$(pods_of "$DEPLOYMENT")
  leftover=0
  for old in $OLD_PODS; do
    case " $current " in *" $old "*) leftover=1 ;; esac
  done

  desired=$(kubectl -n "$NS" get "deployment/${DEPLOYMENT}" -o jsonpath='{.spec.replicas}')
  ready=$(kubectl -n "$NS" get "deployment/${DEPLOYMENT}" -o jsonpath='{.status.readyReplicas}')
  ready="${ready:-0}"

  if [ "$leftover" -eq 0 ] && [ "$ready" = "$desired" ]; then
    break
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    touch "$STOP_FILE"; wait "$LOAD_PID" 2>/dev/null
    say "  [HATA] guncelleme 5 dakikada tamamlanmadi"
    exit 1
  fi
  sleep 1
done

# Kisa bir kuyruk: son endpoint degisikliginin kube-proxy'lere yayilmasi asenkron.
sleep 3
touch "$STOP_FILE"
wait "$LOAD_PID" 2>/dev/null

# --- 4) Say ---
TOTAL=$(wc -l < "$RESULT_FILE" | tr -d ' ')
NOT_OK=$(awk '$1 != "200"' "$RESULT_FILE" | wc -l | tr -d ' ')
SLOW=$(awk -v limit="$SLOW_LIMIT" '$2 + 0 > limit' "$RESULT_FILE" | wc -l | tr -d ' ')
WORST=$(awk 'BEGIN{m=0} {if ($2+0 > m) m=$2+0} END{printf "%.2f", m}' "$RESULT_FILE")

say ""
say "--------------------------------------------"
say "  istek: ${TOTAL}   200 olmayan: ${NOT_OK}   ${SLOW_LIMIT}sn'den yavas: ${SLOW}"
say "  en yavas cevap: ${WORST} sn"
if [ "$NOT_OK" -gt 0 ]; then
  say "  gorulen kodlar:"
  awk '$1 != "200" {print $1}' "$RESULT_FILE" | sort | uniq -c | sed 's/^/    /'
fi
say "--------------------------------------------"

# Hic istek atilamadiysa test gecmis SAYILMAZ: yesil bir sonuc, olculmemis bir
# iddiadan daha kotudur.
if [ "$TOTAL" -lt 20 ]; then
  say "  [HATA] yeterli istek atilamadi (${TOTAL}); olcum anlamsiz"
  exit 1
fi

[ "$NOT_OK" -eq 0 ] && [ "$SLOW" -eq 0 ]
