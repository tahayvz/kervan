-- Stok DUZELTMELERI (ADR-0021).
--
-- Mal kabulu (V3) "disaridan mal geldi" der. Bu tablo farkli bir seyi kaydeder:
-- "bizim sayimiz yanlismis". Ikisi ayni islem DEGILDIR:
--
--   makbuz   -> bir OLAY. Disarida bir sey oldu, biz kaydediyoruz.
--   duzeltme -> bir IDDIA. Sistemdeki sayi gercegi yansitmiyor, degistiriyoruz.
--
-- Iddia olan seyin GEREKCESI ve SAHIBI olmak zorundadir. Stok parayla olculur;
-- "kim, ne zaman, ne kadar, neden" sorularinin cevabi yoksa duzeltme yetkisi
-- denetlenemez bir yetkidir.
--
-- NEDEN AYRI TABLO, NEDEN stock_receipts'e negatif satir DEGIL
-- ------------------------------------------------------------
-- Negatif miktarli bir "makbuz" yazmak kolay olurdu ama iki farkli olayi tek
-- kovaya atardi: "bu ay ne kadar mal girdi" sorusu artik cevaplanamazdi, cunku
-- kirilan mallar da ayni tabloda eksi olarak dururdu.
--
-- IDEMPOTENTLIK
-- -------------
-- adjustment_id BIRINCIL ANAHTAR; makbuzdaki desenin aynisi. Istemci zaman asimi
-- alip yeniden denediginde miktar iki kez uygulanmaz.
CREATE TABLE stock_adjustments (
    adjustment_id VARCHAR(64) PRIMARY KEY,
    sku           VARCHAR(64) NOT NULL,
    -- Pozitif ya da negatif olabilir, ama SIFIR OLAMAZ: hicbir sey degistirmeyen
    -- bir duzeltme yalnizca denetim izini kirletir.
    delta         INTEGER     NOT NULL CHECK (delta <> 0),
    -- Serbest metin DEGIL, sinirli bir liste. Serbest metin yazilsaydi "kirik",
    -- "kırık", "hasarli", "damaged" hepsi ayri deger olur ve "bu ay ne kadar mal
    -- kirildi" sorusu hic cevaplanamazdi.
    reason        VARCHAR(32) NOT NULL,
    -- Aciklama. reason = OTHER ise ZORUNLU (uygulama katmaninda denetleniyor):
    -- gerekcesiz bir "diger", gerekce yazmamakla aynidir.
    note          VARCHAR(500),
    -- Token'daki `sub`. Kullanici adi DEGIL: kullanici adi degisir, sub degismez.
    adjusted_by   VARCHAR(64) NOT NULL,
    adjusted_at   TIMESTAMPTZ NOT NULL
);

-- Denetim izi OKUNABILIR olmali. "Bu SKU'ya ne oldu?" sorgusu icin.
CREATE INDEX idx_stock_adjustments_sku ON stock_adjustments (sku, adjusted_at DESC);
