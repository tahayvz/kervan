# ADR-0005: Dağıtık tutarlılık — Saga (orchestration), koreografi değil

- **Durum:** Accepted
- **Tarih:** 2026-07-14
- **Karar verenler:** Kervan mimari ekibi

## Bağlam
Sipariş oluşturma **üç servisi** kapsar: Order, Payment, Inventory. Bunların hepsi
tutarlı olmalı: stok rezerve edilmeli, ödeme alınmalı, sipariş onaylanmalı. Herhangi
bir adım başarısızsa **öncekiler geri alınmalı** (telafi). Klasik ACID transaction
servis sınırını aşamaz.

## Karar
**Saga** deseni kullanılır — bir dizi lokal transaction; her ileri adımın bir
**telafi (compensation)** adımı vardır. Koordinasyon **orchestration** ile yapılır:
Order servisi merkezî **orkestratör**dür, durumu `saga_state` tablosunda tutar.

```
OrderCreated
  → ReserveStock ──ok──▶ ProcessPayment ──ok──▶ ConfirmOrder ✅
                              │
                              └─fail─▶ ReleaseStock ─▶ CancelOrder (compensation)
```

## Değerlendirilen alternatifler
- **2PC / dağıtık transaction:** Servis sınırında ölçeklenmez, kilitlenme, kırılgan.
  → **Elendi** (ayrıca ADR-0004'te de reddedildi).
- **Choreography-based Saga (koreografi):** Merkezî orkestratör yok; her servis
  bir event'i dinler ve bir sonrakini yayınlar. Az bağ ama akış **görünmezleşir**
  ("event'ler nereye gidiyor?"), döngü/karmaşa riski, hata ayıklaması zor. Az sayıda
  adımda güzel, ama çok adımlı iş akışında takibi güçleşir. → **Bu akış için elendi.**
- **Orchestration-based Saga (seçilen):** Akış tek yerde (orkestratör) açıkça görünür;
  durum makinesi ile yönetilir; hata/telafi mantığı merkezî ve okunur.

## Sonuçlar
- **Olumlu:** İş akışı **açık ve izlenebilir** (durum makinesi + `saga_state`);
  telafi mantığı merkezî; yeni adım eklemek kolay; dağıtık trace ile birebir eşleşir.
- **Olumsuz / ödünler:** Orkestratör bir tür merkez olur (koreografiye göre biraz
  daha bağ); orkestratörün kendisi dayanıklı olmalı. **Eventual consistency**:
  sistem kısa süre "ara durumda" kalır — kullanıcı arayüzü bunu yansıtmalı
  (örn. "siparişiniz işleniyor").
- **Zorunluluk:** Tüm adımlar/tüketiciler **idempotent** (ADR-0004 at-least-once ile
  uyumlu); her adım tekrar gelse yan etki bir kez oluşur.
