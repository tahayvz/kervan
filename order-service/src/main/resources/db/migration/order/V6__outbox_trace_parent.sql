-- İzleme bağlamını (trace context) outbox satırının içinde taşır.
--
-- Neden sütun gerekiyor?
-- Olay Kafka'ya doğrudan yazılmıyor: önce bu tabloya yazılıyor, sonra Debezium
-- veritabanının değişiklik günlüğünden okuyup yayınlıyor. Yayınlandığı an ile
-- siparişi alan HTTP isteği arasında teknik hiçbir bağ yoktur — başka süreç,
-- başka iş parçacığı, bazen dakikalar sonrası. Kütüphanelerin izleme bağlamını
-- otomatik taşıması bu yüzden çalışmaz; yazan taraf bağlamı VERİNİN YANINA
-- koymak zorundadır.
--
-- Biçim W3C `traceparent` başlığıdır:
--   00-<32 hex trace id>-<16 hex span id>-<01|00 örneklendi mi>
-- Bugün 55 karakter tutar; 64 sınırı sürüm alanı büyürse yer bırakır.
--
-- NULL olabilir. İzleme kapalıyken ya da olayı üreten kod bir istek bağlamında
-- değilken (zamanlanmış iş gibi) taşınacak bağlam yoktur. O durumda tüketici
-- yeni bir iz başlatır, olay yine de yayınlanır: izleme bir teşhis aracıdır,
-- iş akışının ön koşulu değildir.
ALTER TABLE outbox_messages
    ADD COLUMN trace_parent VARCHAR(64);
