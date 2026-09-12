# Keycloak realm'i

`kervan-realm.json` konteyner ilk açıldığında **otomatik** içe aktarılır
(`start-dev --import-realm`).

## YALNIZCA LOKAL GELİŞTİRME

Buradaki kullanıcı ve parolalar açıkça sahtedir ve yalnızca docker compose
ortamında çalışır. Üretimde realm elle ya da IaC ile kurulur; **bu dosya oraya
kopyalanmaz.**

| Kullanıcı | Parola | Rol |
|---|---|---|
| `musteri` | `musteri` | CUSTOMER |
| `yonetici` | `yonetici` | ADMIN |

İstemci: `kervan-cli` (public). **Direct access grant** (parola ile token) yalnızca
lokal denemeler ve yük testi için açık; üretimde authorization code + PKCE kullanılır.

Token almak:

```bash
curl -s -X POST http://localhost:8080/realms/kervan/protocol/openid-connect/token \
  -d grant_type=password -d client_id=kervan-cli \
  -d username=musteri -d password=musteri | python3 -m json.tool
```

## Neden açıklamalar bu dosyada, JSON'un içinde değil?

**JSON'da yorum yoktur.** Dosyada bir süre `"_comment"` alanları duruyordu; okunur
görünüyorlardı ama Keycloak'ın içe aktarıcısı tanımadığı alanı **reddediyor**:

```
ERROR: Unrecognized field "_comment" (class RealmRepresentation), not marked as ignorable
```

Sonuç: realm hiç içe aktarılmadı ve Keycloak hiç açılmadı. Uzun süre fark edilmedi,
çünkü testler kendi imzaladıkları token'ları kullanıyor ve Keycloak'a hiç ihtiyaç
duymuyor — hata ancak sistemin tamamı ilk kez birlikte çalıştırıldığında çıktı.

## Apple Silicon notu

Keycloak imajının ARM sürümündeki Java çalışma zamanı bu makinede çöküyor
(`SIGILL ... System.registerNatives ... linux-aarch64`). Compose bu yüzden amd64
sürümünü **sindirim (digest) ile** sabitliyor. Teşhis tek komut:

```bash
docker run --rm --entrypoint java <imaj> -version
```

Aynı sorun `connect` ve `elasticsearch` imajlarında da çıkmıştı.
