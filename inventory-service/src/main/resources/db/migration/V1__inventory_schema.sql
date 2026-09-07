-- Stok şeması.
--
-- İki tablo, iki farklı soruyu cevaplar:
--   stock_items   -> "şu anda ne kadar var?"
--   reservations  -> "kim, hangi sipariş için ne kadarını tuttu?"
--
-- Ayrı durmalarının sebebi: ayırma geri alınabilir bir işlemdir (Saga telafisi) ve
-- geri almak için neyin tutulduğunu bilmek gerekir. Yalnızca miktarı düşseydik,
-- ödeme başarısız olduğunda ne kadarını geri vereceğimizi bilemezdik.

CREATE TABLE stock_items (
    sku                VARCHAR(64) PRIMARY KEY,
    available_quantity INTEGER     NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER     NOT NULL CHECK (reserved_quantity >= 0),
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE reservations (
    id         UUID        PRIMARY KEY,
    -- Sipariş başına EN FAZLA BİR ayırma. Bu kısıt, idempotentliğin kendisidir:
    -- aynı ReserveStock komutu iki kez işlenirse ikincisi burada durur. Ayrı bir
    -- "işlenmiş mesajlar" tablosu tutmaya gerek kalmaz — ki o tablonun da
    -- temizlenmesi, senkron kalması ve doğru anahtarla yazılması gerekirdi.
    order_id   VARCHAR(64) NOT NULL UNIQUE,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reservation_lines (
    id             BIGSERIAL   PRIMARY KEY,
    reservation_id UUID        NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    sku            VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_lines_reservation ON reservation_lines (reservation_id);

-- Outbox: order-service'teki ile aynı desen (ADR-0004). Stok değişikliği ile onu
-- duyuran olay aynı transaction'da yazılır; ikisi ya birlikte olur ya hiç olmaz.
--
-- order-service'ten bir farkı var: burada published_at / attempts sütunları YOK.
-- Bu servisin uygulama içi yayıncısı yok; olayları Debezium taşır ve Debezium
-- tabloya hiç dokunmaz. Kullanılmayacak bir "yayınlandı" sütunu tutmak, ileride
-- birinin ona bakıp yanlış sonuç çıkarmasına davetiye olurdu.
CREATE TABLE outbox_messages (
    id             UUID        PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        BYTEA       NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL
);

-- Temizlik "şu tarihten eski" diye sorar; indeks o sorgu içindir.
CREATE INDEX idx_outbox_occurred_at ON outbox_messages (occurred_at);
