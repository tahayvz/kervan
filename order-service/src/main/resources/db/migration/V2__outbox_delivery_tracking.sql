-- Outbox teslimat takibi.
--
-- Neden: Bir kayıt kalıcı olarak gönderilemiyorsa (örn. payload broker'ın
-- max.request.size sınırını aşıyor), yayıncı her turda ona takılır ve arkasındaki
-- hiçbir olay çıkamaz — baş tıkanması (head-of-line blocking). Deneme sayacı,
-- böyle bir kaydın sınırlı sayıda denendikten sonra kenara alınmasını sağlar:
-- kuyruk akmaya devam eder, sorunlu kayıt incelenmek üzere tabloda kalır.

ALTER TABLE outbox_messages
    ADD COLUMN attempts        INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error      TEXT;

-- Yayıncının okuduğu sorgu artık deneme sayısını da filtreliyor. Kısmi indeks
-- yalnızca gerçekten aday olan satırları kapsar.
DROP INDEX IF EXISTS idx_outbox_unpublished;

CREATE INDEX idx_outbox_deliverable
    ON outbox_messages (occurred_at)
    WHERE published_at IS NULL;

-- Kenara alınmış kayıtları bulmak için: izleme/alarm bu sorguyu kullanır.
--   SELECT * FROM outbox_messages
--   WHERE published_at IS NULL AND attempts >= <max> ORDER BY last_attempt_at DESC;
