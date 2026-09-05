# ADR-0007: Ağ geçidi kimlik doğrular, yetkiyi servisler denetler

- **Durum:** Accepted
- **Tarih:** 2026-09-05
- **Karar verenler:** Taha Yavuz

## Bağlam (Context)

Faz 2a'da `order-service` bir OAuth2 resource server oldu: token'ı doğruluyor, rolleri
okuyor ve kayıt bazlı sahipliği (`Caller.owns()`) denetliyor. Faz 2b ile önüne tek giriş
noktası olarak bir ağ geçidi (Spring Cloud Gateway) geliyor.

Soru şu: güvenlik denetimi nerede yapılmalı?

Çatışan güçler:

- **Tekrar.** Kural iki yerde dururken ikisini de doğru tutmak gerekir.
- **Erken kesme.** Kimliksiz trafiğin arka servislere hiç ulaşmaması istenir.
- **Derinlik.** Ağ geçidi tek savunma hattı olursa, ağ içinden servise doğrudan giden
  bir istek denetimsiz kalır.
- **Veri erişimi.** "Bu sipariş senin mi?" sorusunun cevabı veritabanındadır; ağ geçidi
  veriyi görmez.

## Karar (Decision)

**Ağ geçidi kimlik doğrular, yetkiyi servisler denetler.**

- Ağ geçidi: token geçerli mi? Değilse istek servislere ulaşmadan 401 döner.
  Katalog okumaları (`GET /api/v1/products/**`) herkese açık kalır.
- Servisler: hangi rol ne yapabilir, ve bu kayıt bu kullanıcıya mı ait.
- Token `Authorization` başlığında olduğu gibi aşağı taşınır; servisler kendi
  doğrulamalarını yapmaya devam eder.

## Değerlendirilen alternatifler (Considered options)

- **Tüm yetkiyi ağ geçidine taşımak** — Tek yerde toplanır, servisler sadeleşir.
  Elendi: kayıt bazlı sahiplik ağ geçidinde **yapılamaz**, çünkü veri orada değil.
  Yetkinin bir kısmı zorunlu olarak serviste kalacaksa, ikiye bölmek en kötü seçenek
  olur — hangi kuralın nerede olduğu belirsizleşir.

- **Rol denetimini her iki katmanda da yapmak (savunma derinliği)** — İlk bakışta
  güvenli görünür. Elendi: aynı kural iki dosyada yaşar. Bugün eşit olan iki liste, bir
  rol eklendiğinde kayar; hangisinin doğru olduğunu söyleyecek kimse olmaz. Sessiz
  şekilde ya çok gevşek ya da çok sıkı davranan bir sistem kalır.

- **Ağ geçidinde hiç güvenlik olmaması, her şeyin servislerde kalması** — En az tekrar.
  Elendi: kimliksiz her istek arka servislere kadar gider. Ağ geçidinin var olma
  sebeplerinden biri, geçersiz trafiği kenarda kesmektir.

## Sonuçlar (Consequences)

- **Olumlu:** Yetki kuralı tek yerde yaşar, kaymaz. Kimliksiz trafik kenarda kesilir;
  arka servisler yorulmaz. Servisler kendi başlarına da güvenlidir: ağ geçidi
  atlanırsa açıkta kalmazlar.

- **Olumsuz / ödünler:** İki katman iki kez JWT doğruluyor. Doğrulama imza kontrolüdür
  ve JWK anahtarları önbelleğe alınır, yani maliyet küçüktür. Karşılığında ağ geçidi
  tek arıza noktası olmaktan çıkar. Ayrıca "yetki nerede?" sorusunun cevabı tek kelime
  değil: kimlik ağ geçidinde, yetki serviste. Bu ADR o yüzden var.

- **Takip / risk:** Ağ geçidine yeni rota eklenirken, arkasındaki servisin kendi yetki
  denetiminin olduğu **doğrulanmalıdır**. Denetimsiz bir servisi ağ geçidine bağlamak,
  onu kimliği doğrulanmış herkese açar. Rate limiting bu ADR'nin kapsamında değildir;
  Faz 7'ye (Resilience4j) bırakılmıştır.

- **Bu şart ilk turda karşılanmadı.** Ağ geçidi eklendiğinde `catalog-service`'te
  hiçbir yetki denetimi yoktu. Sonuç: CUSTOMER rolüyle alınmış herhangi bir token
  `DELETE /api/v1/products/{id}` çağırıp ürünü kalıcı silebiliyordu. Ağ geçidi
  "authenticated" diyerek korunuyormuş izlenimi veriyordu. Katalog artık kendi
  `SecurityConfig`'ine sahip: okuma herkese açık, katalogu **değiştiren** her şey
  `ADMIN` ister. Sekiz test bunu sabitliyor.

  Ders: bu maddeyi yazmak yetmiyor, yeni rota eklerken **uygulamak** gerekiyor.
