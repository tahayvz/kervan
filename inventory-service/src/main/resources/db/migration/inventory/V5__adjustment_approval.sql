-- Buyuk duzeltmelerde IKINCI ONAY (ADR-0022).
--
-- NEDEN
-- -----
-- ADR-0021 stok duzeltmesini acti ve kendi risk bolumunde su satiri birakti:
-- "ADMIN olan herkes SINIRSIZ duzeltebiliyor."
--
-- Stok paradir. Stogu duzeltme yetkisi, ayni zamanda EKSIGI GIZLEME yetkisidir:
-- depodan mal alan biri sisteme "kayip" yazip kapatabilir. Denetim izi bunu
-- KAYDEDER ama kimse bakmazsa DURDURMAZ.
--
-- Bankacilikta ve depoculukta bunun adi "dort goz kurali": buyuk bir hareket icin
-- IKI AYRI kisi gerekir. Asil koruma esigin kendisi degil, ISTEYENIN KENDI
-- ISTEGINI ONAYLAYAMAMASIDIR.
--
-- NEDEN YENI TABLO DEGIL
-- ----------------------
-- Bir duzeltmenin artik yasam dongusu var: istendi -> onaylandi/reddedildi.
-- Bu, ayri bir varlik degil AYNI kaydin durumu. Ikinci bir tabloya tasimak,
-- "hangi duzeltme uygulandi" sorusunu iki yere bakmadan cevaplanamaz hale getirirdi.
--
-- MEVCUT SATIRLAR
-- ---------------
-- Bu goc oncesinde yazilmis her duzeltme zaten UYGULANMISTI; onlar APPLIED.
-- Varsayilan deger o yuzden APPLIED ve NOT NULL.

ALTER TABLE stock_adjustments
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    -- Onaylayan/reddeden kisinin token'daki `sub` alani. Bekleyen kayitlarda NULL.
    ADD COLUMN decided_by VARCHAR(64),
    ADD COLUMN decided_at TIMESTAMPTZ;

-- Veritabani seviyesinde iki kural. Uygulama da denetliyor, ama kod degisir ve
-- veri kalir: kisit, kuralin unutulamayacagi tek yerdir.
ALTER TABLE stock_adjustments
    ADD CONSTRAINT stock_adjustments_status_valid
        CHECK (status IN ('APPLIED', 'PENDING', 'APPROVED', 'REJECTED')),
    -- Karar verilmis bir kaydin karar vereni ve zamani OLMAK ZORUNDA; bekleyen bir
    -- kaydin OLMAMALI. Denetim izinin degeri tamliginda.
    ADD CONSTRAINT stock_adjustments_decision_complete
        CHECK (
            (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
            OR
            (status IN ('APPLIED', 'PENDING') AND decided_by IS NULL AND decided_at IS NULL)
        );

-- "Onay bekleyenler" sorgusu icin. Bekleyen kayit azdir; kismi indeks tam bu ise yarar.
CREATE INDEX idx_stock_adjustments_pending
    ON stock_adjustments (adjusted_at DESC)
    WHERE status = 'PENDING';
