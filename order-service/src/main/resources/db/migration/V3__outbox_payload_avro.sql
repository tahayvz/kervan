-- Outbox payload'ı artık Avro ile serileştirilmiş ikili veri (ADR-0008).
--
-- Neden tip değişiyor: Avro çıktısı metin değildir. TEXT sütuna yazmak için
-- base64'e çevirmek gerekirdi; bu hem boyutu ~%33 büyütür hem de Debezium'un
-- taşıdığı satırda fazladan bir kodlama katmanı bırakırdı.

-- Bu migration'ın güvenli çalışması için outbox'ın BOŞALTILMIŞ olması gerekir.
-- Tablodaki eski kayıtlar JSON'dur; baytlara çevrilseler bile Avro olarak
-- okunamazlar ve tüketicide çözülemeyen mesaj olarak birikirlerdi.
--
-- Doğru dağıtım sırası:
--   1. Eski sürüm çalışmaya devam ederken outbox'ın boşalmasını bekle
--      (SELECT count(*) FROM outbox_messages WHERE published_at IS NULL → 0)
--   2. Yeni sürümü dağıt; bu migration çalışır.
--
-- Sıra atlanırsa migration hata verir ve dağıtım durur. Sessizce bozuk veri
-- üretmektense dağıtımı durdurmak tercih edilmiştir.
DO $$
DECLARE
    pending_count BIGINT;
BEGIN
    SELECT count(*) INTO pending_count
    FROM outbox_messages
    WHERE published_at IS NULL;

    IF pending_count > 0 THEN
        RAISE EXCEPTION
            'outbox_messages bosaltilmadan payload tipi degistirilemez: % adet yayinlanmamis kayit var. Once eski surumun bunlari gondermesini bekleyin.',
            pending_count;
    END IF;
END $$;

-- Yayınlanmış kayıtlar yalnızca geçmiş kaydıdır; baytlara çevrilmeleri sorun değil.
ALTER TABLE outbox_messages
    ALTER COLUMN payload TYPE BYTEA USING convert_to(payload, 'UTF8');
