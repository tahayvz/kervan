-- Stok GIRISI: mal kabul makbuzlari.
--
-- Bu tablodan once stok yalnizca DUSEBILIYORDU. Ayirma ve geri birakma vardi,
-- girisi yoktu; yeni bir SKU'nun stok kaydini bu servis hic acamiyordu ve
-- tohumlama betigi bu yuzden veritabanina DOGRUDAN yaziyordu -- yani baska bir
-- servisin verisine disaridan dokunuyordu (ADR-0001'in tam tersi).
--
-- NEDEN MAKBUZ, NEDEN MUTLAK DEGER DEGIL (ADR-0019)
-- -------------------------------------------------
-- "Stok artik 500 olsun" demek iki seyi bozar:
--   1) Ayni anda gelen iki giris birbirini SESSIZCE ezer (kayip guncelleme).
--   2) "Mal geldi" ile "sayim duzeltmesi" ayni islem olur; oysa biri olay,
--      digeri bir iddia.
-- Makbuz toplamalidir: iki giris de sayilir.
--
-- IDEMPOTENTLIK
-- -------------
-- receipt_id BIRINCIL ANAHTAR. Ayni makbuz iki kez gonderilirse ikincisi burada
-- durur; miktar iki kez eklenmez.
--
-- Bu, reservations.order_id UNIQUE ile AYNI desendir ve ayni gerekceyle: is
-- kuralinin kendisinde zaten dogal bir anahtar var. Ayri bir "islenmis istekler"
-- tablosu tutmak, senkron kalmasi ve temizlenmesi gereken ikinci bir sey yaratirdi.
CREATE TABLE stock_receipts (
    receipt_id  VARCHAR(64) PRIMARY KEY,
    sku         VARCHAR(64) NOT NULL,
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    received_at TIMESTAMPTZ NOT NULL
);

-- "Bu SKU'ya ne zaman ne girdi?" sorgusu icin. Denetim izinin okunabilir olmasi
-- makbuzun varlik sebebinin bir parcasi.
CREATE INDEX idx_stock_receipts_sku ON stock_receipts (sku, received_at DESC);
