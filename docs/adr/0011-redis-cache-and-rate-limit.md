# ADR-0011: Redis — önbellek ve hız sınırlama (dağıtık kilit DEĞİL)

- **Durum:** Accepted
- **Tarih:** 2026-09-11
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
İki ihtiyaç var:

1. **Katalog okuması tekrarlanıyor.** Aynı ürün sayfası defalarca açılır; her seferinde
   MongoDB'ye gitmek gereksiz.
2. **Açık uçlar korumasız.** Arama ve katalog listeleme kimlik istemiyor. Tek bir
   istemci saniyede binlerce istek atıp servisi meşgul edebilir.

Yol haritası bir üçüncüsünü de sayıyordu: **dağıtık kilit**.

## Karar

**Redis iki iş için kullanılır: önbellek ve hız sınırlama sayaçları.**

**Dağıtık kilit kullanılmaz.** Gerekçesi aşağıda; bu, atlanmış bir madde değil,
verilmiş bir karardır.

### Önbellek (catalog-service)

Ürün okumaları Redis üzerinden önbelleklenir. Üç seçim:

- **Sarmalayıcı (decorator), anotasyon değil.** Önbellek `ProductRepository` portunu
  uygulayıp gerçek uygulamayı sarar. Uygulama katmanı önbelleğin varlığını bilmez;
  yarın kaldırılsa orada tek satır değişmez.
- **Yazmada silinir, güncellenmez.** Güncellemek daha hızlı görünür ama yanlış
  olabilir: kaydedilen nesne ile veritabanının döndürdüğü aynı olmayabilir (sürüm
  alanı, sunucu tarafı varsayılanlar). Silmek, bir sonraki okumanın doğru veriyi
  getirmesini garanti eder.
- **Arama önbelleklenmez.** Sorgu uzayı çok geniş (metin × süzgeç × sayfa) ve isabet
  oranı düşük olur; ayrıca hangi sorgu sonuçlarının bayatladığını bilmek mümkün değil.
  Aramanın hızlı olması gerekiyorsa yeri arama servisidir (ADR-0010).

**Redis erişilemezse istek düşmez**, veritabanına gidilir. Önbellek bir hızlandırmadır;
onun arızası katalogun arızası olmamalı. Bağlantı zaman aşımları bu yüzden kısa.

### Hız sınırlama (api-gateway)

Jeton kovası: saniyede sabit sayıda jeton dolar, kova bir üst sınıra kadar birikir.
Ani yığılma kovadan karşılanır, sürekli yüksek hız dolum hızına iner.

Sayaçlar **Redis'te** tutulur. Bellekte tutulsaydı her ağ geçidi kopyası kendi sınırını
uygular ve gerçek sınır kopya sayısıyla çarpılırdı.

Anahtar: kimliği bilinen istek **kullanıcıya**, bilinmeyen istek **IP'ye** göre
sınırlanır. Yalnızca IP kullanılsaydı aynı kurumsal ağdaki kullanıcılar tek kotayı
paylaşırdı; yalnızca kullanıcı kullanılsaydı korunması gereken asıl yüzey — açık uçlar —
sınırsız kalırdı.

## Dağıtık kilit neden yok

Sistem, dağıtık kilide **ihtiyaç duymayacak şekilde** kuruldu:

| Sorun | Çözüm | Nerede |
|---|---|---|
| İki sipariş aynı son ürünü alamaz | `SELECT ... FOR UPDATE`, sabit SKU sırası | inventory-service |
| Aynı siparişin iki olayı yan yana işlenmesin | Saga satırı kilitlenerek okunur | order-service |
| Aynı komut iki kez işlenmesin | Doğal anahtar + benzersizlik kısıtı | inventory, payment |
| İki kopya aynı outbox satırını göndermesin | `FOR UPDATE SKIP LOCKED` | order-service |

Bunların hepsi **veritabanının** garantileri ve veriyle aynı transaction'da. Redis'e bir
kilit koymak, "kimde kilit var" sorusunun ikinci bir cevabını yaratırdı — ve iki cevap
çeliştiğinde hangisinin doğru olduğunu söyleyecek bir kural yok.

Tek aday, outbox temizliğinin birden çok kopyada aynı anda çalışmasıydı. O da zararsız:
iki kopya aynı satırları seçse bile biri siler, diğeri sıfır satır siler. İsraf var,
hata yok. Bir kilit eklemek, olmayan bir problemi çözmek için gerçek bir karmaşıklık
eklemek olurdu.

**Gerekirse** (örneğin yalnızca tek kopyada çalışması gereken bir bakım işi çıkarsa)
tercih sırası şudur: önce veritabanının kendi kilidi, sonra kilidi kütüphaneye bırakan
bir çözüm (ShedLock), en sonda elle yazılmış Redis kilidi. Elle yazılan kilit, süre
aşımı ve sahiplik doğrulaması gibi ayrıntıları yanlış yapmaya çok açıktır.

## Değerlendirilen alternatifler

- **Önbelleksiz devam** — En basiti. Katalog okuması bu ölçekte zaten hızlı. Ama tekrar
  eden okuma gerçek ve Redis'in getirdiği karmaşıklık sarmalayıcıyla tek sınıfta
  kalıyor. **Seçilmedi** ama yakın bir karardı.
- **Uygulama içi önbellek (Caffeine)** — Redis gerektirmez, daha hızlıdır. Ama her
  kopyanın kendi önbelleği olur: bir kopyadaki yazma diğerindeki bayat kaydı temizlemez
  ve kullanıcı hangi kopyaya düştüğüne göre farklı veri görür. **Elendi.**
- **Hız sınırını yük dengeleyiciye bırakmak** — Üretimde makul. Ama proje dengeleyicisiz
  çalışıyor ve sınırın kullanıcıya göre değişmesi isteniyor; dengeleyici JWT'yi
  okumadan bunu yapamaz. **Elendi.**

## Sonuçlar

- **Olumlu:** Tekrarlanan katalog okumaları veritabanına inmez. Açık uçlar tek bir
  istemcinin yüküne karşı korunur ve sınır kopya sayısından bağımsızdır.

- **Olumsuz / ödünler:**
  - Ayakta tutulacak bir bileşen daha. Ama kaybolması felaket değil: önbellek boş
    döner, sayaçlar sıfırlanır.
  - **Önbellek bayatlığı.** Yazma anında silme yapılıyor, yani pencere dar; ama aynı
    ürün için yazma ile okuma yarışırsa okuyan eski değeri görebilir. Katalog için
    kabul edilebilir, sipariş ya da stok için olmazdı — orada önbellek yok.
  - Hız sınırı IP'ye düştüğünde, ağ geçidi bir yük dengeleyicinin arkasındaysa herkes
    tek kotaya düşer. Çözümü güvenilen bir `X-Forwarded-For` başlığını okumaktır; o
    başlık ancak dengeleyicinin yazdığına güvenilebiliyorsa okunabilir, aksi hâlde
    istemci uydurup sınırı sıfırlar. Proje dengeleyicisiz çalıştığı için bugün gerekli
    değil.

## İlgili kararlar
- ADR-0010 (Arama okuma modeli) — aramanın neden önbelleklenmediği
- ADR-0006 (Polyglot persistence) — katalogun MongoDB'de olması
