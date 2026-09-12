// Kervan — sentetik yuk (Faz 10).
//
// Amac "sistem kac istek kaldiriyor" degil. Amac, Faz 6 ve 7'de kurulan seylerin
// GERCEKTEN calistigini gormek: panolar dolsun, izler olussun, saga yuk altinda
// yursun ve bir sey kirilirsa nerede kirildigi gorunsun.
//
// Calistirma:
//   docker compose -f infra/docker/docker-compose.yml --profile load run --rm k6
//
// Ayarlar (ortam degiskeni):
//   KERVAN_VUS         es zamanli sanal kullanici (varsayilan 10)
//   KERVAN_DURATION    sure (varsayilan 2m)
//   KERVAN_ORDER_RATE  saniyede siparis (varsayilan 5)
//
// DIKKAT: degisken adlari K6_ ile BASLAMAMALI. K6_ onegi k6'nin KENDI ayar
// onegidir; K6_VUS/K6_DURATION verildiginde k6 senaryolari tamamen ezer ve
// "function 'default' not found in exports" der.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const GATEWAY = __ENV.KERVAN_GATEWAY || 'http://api-gateway:8000';
const KEYCLOAK = __ENV.KERVAN_KEYCLOAK || 'http://keycloak:8080';

// Kendi sayaclarimiz. Sistemin metrikleriyle YAN YANA okunacak: burada "siparis
// verdim" derken Grafana'da saga sayilari ve outbox gecikmesi ne yapiyor?
const ordersPlaced = new Counter('kervan_orders_placed');
const ordersRejected = new Counter('kervan_orders_rejected');
const orderFailureRate = new Rate('kervan_order_failures');

export const options = {
  scenarios: {
    // Gezinen kullanici: cogunluk. Gercek trafik boyledir -- okuma, yazmadan
    // kat kat fazladir.
    browsing: {
      executor: 'constant-vus',
      vus: Number(__ENV.KERVAN_VUS || 10),
      duration: __ENV.KERVAN_DURATION || '2m',
      exec: 'browse',
    },
    // Siparis veren kullanici: azinlik ama pahali is. Saga'yi calistiran budur.
    ordering: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.KERVAN_ORDER_RATE || 5),
      timeUnit: '1s',
      duration: __ENV.KERVAN_DURATION || '2m',
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'order',
    },
  },
  thresholds: {
    // Esikler bir HEDEF degil, bir ALARM. Asilirsa kosu basarisiz sayilir ve
    // "yuk testi calisti, herhalde iyidir" demenin onune gecer.
    'http_req_failed{scenario:browsing}': ['rate<0.02'],
    'http_req_duration{scenario:browsing}': ['p(95)<1500'],
    'kervan_order_failures': ['rate<0.05'],
  },
};

// Token bir kez alinir ve butun sanal kullanicilar paylasir. Her istekte token
// almak, olculen seyi Keycloak'in hizi haline getirirdi.
export function setup() {
  const res = http.post(
    `${KEYCLOAK}/realms/kervan/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'kervan-cli', username: 'musteri', password: 'musteri' },
    { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
  );
  check(res, { 'token alindi': (r) => r.status === 200 });
  const token = res.json('access_token');

  // Urunler tohumlamadan gelir; katalogtan OKUYORUZ ki betik ile tohumlama
  // birbirinden bagimsiz kalsin. Sabit bir SKU listesi gomseydik, tohumlama
  // degistiginde yuk betigi sessizce var olmayan urunler siparis ederdi.
  const products = http.get(`${GATEWAY}/api/v1/products?size=50`);
  check(products, { 'katalog okundu': (r) => r.status === 200 });

  const items = (products.json('items') || []).map((p) => ({
    id: p.id,
    sku: p.sku,
    // Gercek fiyat kullanilir: uydurma bir fiyat, odeme simulatorunun ret
    // sinirini (10000) beklenmedik sekilde asabilir ve butun siparisler
    // reddedilirdi -- yuk testi de "iptal akisi testi"ne donusurdu.
    unitPrice: p.price && p.price.amount ? String(p.price.amount) : '149.90',
  })).filter((p) => p.id && p.sku);

  if (items.length === 0) {
    throw new Error('Katalogta urun yok. Once scripts/seed.sh calistirin.');
  }
  return { token, items };
}

export function browse(data) {
  group('katalog ve arama', () => {
    // Kimliksiz: bu uclar herkese acik (ADR-0007).
    const list = http.get(`${GATEWAY}/api/v1/products?size=20`, { tags: { uc: 'urun-listesi' } });
    check(list, { 'urun listesi 200': (r) => r.status === 200 });

    const product = data.items[Math.floor(Math.random() * data.items.length)];
    const search = http.get(
      `${GATEWAY}/api/v1/search/products?q=${encodeURIComponent(product.sku.slice(0, 3))}`,
      { tags: { uc: 'arama' } },
    );
    check(search, { 'arama 200': (r) => r.status === 200 });
  });

  // Gercek kullanici araliksiz tiklamaz. Bekleme olmasaydi olculen sey trafik
  // degil, k6'nin ne kadar hizli dongu dondugu olurdu.
  sleep(Math.random() * 2 + 0.5);
}

export function order(data) {
  const product = data.items[Math.floor(Math.random() * data.items.length)];
  // Alan adi `lines`, `items` DEGIL. Sozlesme PlaceOrderRequest'te yazili;
  // yanlis ad 400 doner ve yuk testi "her siparis basarisiz" gosterirdi.
  const body = JSON.stringify({
    currency: 'TRY',
    lines: [{
      productId: product.id,
      sku: product.sku,
      quantity: 1 + Math.floor(Math.random() * 3),
      unitPrice: product.unitPrice,
    }],
  });

  const res = http.post(`${GATEWAY}/api/v1/orders`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
    tags: { uc: 'siparis' },
  });

  const created = res.status === 201;
  check(res, { 'siparis 201': () => created });

  if (created) {
    ordersPlaced.add(1);
  } else {
    ordersRejected.add(1);
    // 429 hiz sinirindan gelir ve BEKLENEN bir cevaptir: sistemin kendini
    // korumasidir, ariza degil. Ayri sayilmasi gerekirdi; simdilik hepsi
    // basarisizlik sayiliyor ve esik buna gore secildi.
  }
  orderFailureRate.add(!created);

  sleep(0.2);
}
