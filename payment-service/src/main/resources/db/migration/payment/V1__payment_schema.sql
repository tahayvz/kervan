-- Ödeme şeması.

CREATE TABLE payments (
    id          UUID           PRIMARY KEY,
    -- Sipariş başına EN FAZLA BİR ödeme. İdempotentlik buradan gelir: aynı
    -- ProcessPayment komutu iki kez işlenirse ikincisi bu kısıta takılır ve
    -- müşteriden iki kez tahsilat yapılmaz.
    order_id    VARCHAR(64)    NOT NULL UNIQUE,
    customer_id VARCHAR(64)    NOT NULL,
    -- Ölçek 4: olay sözleşmesindeki decimal ile aynı (ADR-0008). Daha dar bir ölçek
    -- 3 ondalıklı para birimlerini (KWD, BHD, OMR) taşıyamazdı.
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    status      VARCHAR(16)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL
);

-- Outbox: inventory-service'teki ile aynı, gerekçesi ADR-0004. Tahsilat ile onu
-- duyuran olay aynı transaction'da yazılır.
--
-- published_at / attempts sütunları YOK: bu servisin uygulama içi yayıncısı yok,
-- olayları Debezium taşır ve tabloya hiç dokunmaz. Kullanılmayacak bir "yayınlandı"
-- sütunu, ileride birinin ona bakıp yanlış sonuç çıkarmasına davetiye olurdu.
CREATE TABLE outbox_messages (
    id             UUID        PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        BYTEA       NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_occurred_at ON outbox_messages (occurred_at);
