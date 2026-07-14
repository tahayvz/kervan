# Katkı & Çalışma Disiplini

Bu proje, kurumsal bir ekipteki disiplini taklit eder. Amaç sadece kod değil,
**izlenebilir ve savunulabilir** bir mühendislik süreci göstermektir.

## Commit konvansiyonu (Conventional Commits)

Format: `<type>(<scope>): <özet>`

**Type'lar:**
- `feat` — yeni özellik
- `fix` — hata düzeltmesi
- `docs` — dokümantasyon
- `refactor` — davranışı değiştirmeyen iç düzenleme
- `test` — test ekleme/düzenleme
- `build` — build/bağımlılık/CI değişiklikleri
- `chore` — küçük bakım işleri
- `perf` — performans iyileştirmesi

**Örnekler:**
```
docs(foundation): mimari, tech-radar ve ilk 5 ADR eklendi
feat(catalog): ürün CRUD + Flyway şeması + OpenAPI sözleşmesi
test(catalog): Testcontainers ile Postgres entegrasyon testleri
build(ci): PR'da Testcontainers testlerini çalıştıran Actions pipeline
```

Her commit gövdesinde (body) **neden** yapıldığı 1-2 cümleyle açıklanır. Bir lead
için commit geçmişi, projenin canlı dokümantasyonudur.

## Branch stratejisi
- `main` — her zaman yeşil (derlenir + testler geçer)
- Faz/özellik dalları: `phase/1-catalog`, `feat/order-saga` vb.
- Push kararı geliştiriciye aittir (bu repo lokal geliştirme odaklıdır).

## Dal başına tanım (Definition of Done)
Bir faz "bitti" sayılır ancak ve ancak:
- [ ] Kod derleniyor (`mvn verify`)
- [ ] Testler geçiyor (unit + varsa Testcontainers)
- [ ] İlgili dokümantasyon güncellendi (README/ROADMAP/ADR)
- [ ] Yeni bir mimari karar varsa ADR yazıldı
- [ ] Lokalde `docker compose up` ile çalışıyor
