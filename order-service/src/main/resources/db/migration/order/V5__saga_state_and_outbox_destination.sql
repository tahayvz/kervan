-- Faz 4: Saga orchestration.
--
-- İki ekleme var: outbox kayıtlarına hedef konu, ve saga'nın nerede olduğunu tutan
-- tablo.

-- 1) HEDEF KONU
--
-- Şimdiye kadar bu servisin outbox'ından çıkan her şey tek bir konuya gidiyordu ve
-- konu adı Debezium ayarında sabit yazılıydı. Orchestrator artık başka servislere
-- KOMUT da gönderiyor (kervan.inventory.commands, kervan.payments.commands), yani
-- tek hedef yetmiyor.
--
-- Neden aggregate_type'ı konu adı olarak kullanmadık? Debezium'un belgelenmiş yolu
-- odur ve migration gerektirmezdi. Ama o sütun "hangi toplam" sorusunun cevabı;
-- üstüne "nereye gidecek" anlamını yüklemek onu iki anlamlı yapardı. Ayrı sütun,
-- altı ay sonra okuyan için net.
ALTER TABLE outbox_messages
    ADD COLUMN destination VARCHAR(128);

-- Mevcut kayıtların hepsi sipariş olayıdır; hedefleri bilinen tek konudur.
UPDATE outbox_messages SET destination = 'kervan.orders.events' WHERE destination IS NULL;

-- Varsayılan bırakılmıyor: yeni bir mesaj tipi eklendiğinde hedefi YAZILMAK zorunda.
-- Varsayılan olsaydı, hedefi unutulan bir komut sessizce yanlış konuya giderdi.
ALTER TABLE outbox_messages
    ALTER COLUMN destination SET NOT NULL;

-- 2) SAGA DURUMU
--
-- Saga uzun ömürlüdür: adımlar arasında dakikalar geçebilir ve servis bu sırada
-- yeniden başlayabilir. Durum bellekte tutulsaydı her yeniden başlatma devam eden
-- bütün siparişleri unuturdu.
--
-- Sipariş başına tek saga: order_id birincil anahtar. Bu aynı zamanda idempotentliği
-- sağlar — aynı olay iki kez gelirse ikinci işleme durumu zaten ilerlemiş bulur.
CREATE TABLE order_sagas (
    order_id       VARCHAR(64) PRIMARY KEY,
    state          VARCHAR(32) NOT NULL,
    -- Telafi için gereken kimlikler. Stok geri bırakılırken hangi ayırmanın
    -- bırakılacağı, ödeme iade edilirken hangi ödemenin iade edileceği bunlarla
    -- bilinir; sipariş kimliği tek başına yetmez.
    reservation_id VARCHAR(64),
    payment_id     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Sıkışıp kalmış saga'ları bulmak için: izleme bu sorguyu kullanır.
--   SELECT * FROM order_sagas
--   WHERE state NOT IN ('COMPLETED', 'CANCELLED') AND updated_at < now() - interval '15 minutes';
CREATE INDEX idx_order_sagas_state_updated ON order_sagas (state, updated_at);
