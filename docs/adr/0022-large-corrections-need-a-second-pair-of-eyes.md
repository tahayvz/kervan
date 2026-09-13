# ADR-0022: Büyük stok düzeltmeleri ikinci bir onay ister

- **Durum:** Accepted
- **Tarih:** 2026-09-13
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

ADR-0021 stok düzeltmesini açtı ve kendi risk bölümünde şu satırı bıraktı:

> **Yetki tek kademeli** — `ADMIN` olan herkes sınırsız düzeltebilir. Gerçek bir
> kurulumda büyük düzeltmeler ikinci bir onay isterdi.

Sorun teknik değil. **Stoğu düzeltme yetkisi, aynı zamanda eksiği gizleme
yetkisidir.** Depodan mal alan biri sisteme "kayıp" yazıp kapatabilir. ADR-0021'in
getirdiği denetim izi bunu *kaydeder* ama *durdurmaz*: kayıt, kimse bakmadığı sürece
yalnızca bir satırdır.

Bankacılıkta ve depoculukta bunun adı **dört göz kuralı**: belirli bir büyüklüğün
üstündeki hareket için iki ayrı kişi gerekir.

## Karar (Decision)

Mutlak değeri **eşiğin üstünde** olan düzeltmeler doğrudan uygulanmaz. Beklemeye
alınır, stok değişmez, ve **başka bir** `ADMIN` onaylayana kadar öyle kalır.

```
POST /api/v1/stock/{sku}/adjustments          küçükse 200, büyükse 202
POST /api/v1/stock/adjustments/{id}/approve   onayla → stok o anda değişir
POST /api/v1/stock/adjustments/{id}/reject    reddet → stok hiç değişmez
```

Dört kural:

1. **İsteyen kendi isteğini onaylayamaz — ve reddedemez.** Bu kural, ikinci onayın
   *tamamıdır*. Eşik yalnızca hangi düzeltmelerin onaya düşeceğini söyler; korumayı
   sağlayan şey isteyenin karar verememesidir.

2. **Eşik adet üzerinden, para üzerinden değil.** Varsayılan 100, ayarlanabilir.

3. **Ret yolu var.** Yoksa bekleyen kayıtlar sonsuza kadar birikir ve kimse
   "bekleyenler" listesine bakmaz olur.

4. **Miktar onay anında yeniden doğrulanır.** İstek anında geçerliydi ama aradan
   zaman geçti; stok bu sürede düşmüş olabilir.

## Değerlendirilen alternatifler (Considered options)

- **Eşiği paraya bağlamak** — *reddedildi*
  - Artı: daha doğru ölçü. 5000 vida ile 5 televizyon aynı şey değil.
  - Eksi: fiyat `catalog-service`'te duruyor. Eşiği paraya bağlamak, stok
    düzeltmesini katalog servisine **senkron olarak bağımlı** yapardı: katalog
    ayaktayken düzeltme yapılabilir, değilken yapılamaz hâle gelirdi.
  - **Bir kontrolün, korumaya çalıştığı şeyden daha kırılgan olması kabul edilemez.**
    Adet daha kaba bir ölçü ama kendi servisinin içinde duruyor.

- **Ayrı bir onaylayan rolü (`STOCK_APPROVER`)** — *reddedildi*
  - Artı: kimin onaylayabileceği açıkça yönetilir.
  - Eksi: korumayı sağlayan şey rol değil, **isteyenin kendisi olmaması**. Yeni bir
    rol, Keycloak tarafında yönetilecek ikinci bir şey ekler ve tek başına hiçbir
    şeyi engellemez — aynı kişi iki role de sahip olabilir.
  - Gerçek bir kurulumda eklenir; buradaki soru "rol mü" değil "iki kişi mi" idi.

- **Her düzeltmeye onay istemek (eşiksiz)** — *reddedildi*
  - "Bir kutu ezilmiş, 3 adet düş" demek için ikinci kişi aramak gerekirdi. Kimse
    kullanmaz, herkes SQL'e döner ve kontrol *kâğıt üstünde* kalır.
  - Kullanılmayan bir kontrol, olmayan bir kontrolden kötüdür: var sanılır.

- **Ayrı bir `pending_adjustments` tablosu** — *reddedildi*
  - Bir düzeltmenin artık yaşam döngüsü var: istendi → onaylandı/reddedildi. Bu,
    ayrı bir varlık değil **aynı kaydın durumu**. İkinci tabloya taşımak, "hangi
    düzeltme uygulandı" sorusunu iki yere bakmadan cevaplanamaz hâle getirirdi.

- **Sonradan onay (önce uygula, sonra incele)** — *reddedildi*
  - Zaten var olan şey bu: denetim izi. Kapatmak istediğimiz boşluk tam olarak
    "kaydedilmiş ama durdurulmamış" hareketti.

## Sonuçlar (Consequences)

- **Olumlu:** Tek kişi, tek başına büyük bir stok hareketi yaratamaz.
- **Olumlu:** Denetim izi artık **iki** kişiyi tutuyor: isteyen ve karar veren.
- **Olumlu:** Kural iki yerde birden duruyor — alan modelinde (`approvedBy`) ve
  veritabanı kısıtında. Kod değişir, veri kalır.
- **Olumlu:** Metrik yalnızca **gerçekten olan** hareketi sayıyor. Bekleyen ve
  reddedilen düzeltmeler ölçüme girmiyor; ölçüm noktası bu yüzden yazma anından
  karar anına taşındı.
- **Olumsuz / ödün:** Adet eşiği kaba. 100 adet vida önemsiz, 100 adet televizyon
  değil. Bilinen ve kabul edilmiş bir eksiklik.
- **Olumsuz / ödün:** Bekleyen düzeltmeler için bir liste ucu **yok**. Onaylayan
  kişi kimliği başka bir yoldan öğrenmek zorunda. Kısmi indeks (`WHERE status =
  'PENDING'`) o sorgu için hazır duruyor ama uç yazılmadı.
- **Takip / risk:** Bekleyen kayıtlara zaman aşımı yok. Kimse karar vermezse sonsuza
  kadar bekler.
- **Takip / risk:** Onay bir olay yayınlamıyor. Gerçek bir sistemde "onayın bekliyor"
  bildirimi giderdi; bu projede tüketicisi olmayan olay yazılmıyor (ADR-0019).
