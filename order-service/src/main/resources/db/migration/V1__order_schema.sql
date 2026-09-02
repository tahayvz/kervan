-- Sipariş şeması ve outbox tablosu.
-- Not: outbox aynı veritabanındadır; outbox'ın tüm değeri sipariş yazımıyla
-- aynı transaction'a girebilmesidir (bkz. OutboxMessage javadoc).

CREATE TABLE orders (
    id            UUID PRIMARY KEY,
    customer_id   VARCHAR(64)     NOT NULL,
    total_amount  NUMERIC(19, 4)  NOT NULL,
    currency      VARCHAR(3)      NOT NULL,
    status        VARCHAR(16)     NOT NULL,
    placed_at     TIMESTAMPTZ     NOT NULL,
    updated_at    TIMESTAMPTZ     NOT NULL
);

CREATE INDEX idx_orders_customer_placed ON orders (customer_id, placed_at DESC);

CREATE TABLE order_lines (
    id          BIGSERIAL PRIMARY KEY,
    order_id    UUID            NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id  VARCHAR(64)     NOT NULL,
    sku         VARCHAR(64)     NOT NULL,
    quantity    INTEGER         NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(19, 4)  NOT NULL
);

CREATE INDEX idx_order_lines_order ON order_lines (order_id);

CREATE TABLE outbox_messages (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);

-- Yayıncı yalnızca published_at IS NULL kayıtları okur. Kısmi indeks, tablo
-- büyüdükçe bu sorgunun sabit maliyetli kalmasını sağlar: yayınlanmış milyonlarca
-- satır indekste yer kaplamaz.
CREATE INDEX idx_outbox_unpublished
    ON outbox_messages (occurred_at)
    WHERE published_at IS NULL;
