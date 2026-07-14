-- Kervan — servis başına ayrı veritabanı (database-per-service)
-- Bu script Postgres konteyneri İLK kez ayağa kalkarken bir kez çalışır.
-- Her mikroservis yalnızca kendi veritabanına erişir; başka servisin verisine
-- doğrudan SQL ile dokunmak mimari olarak yasaktır (bkz. docs/ARCHITECTURE.md §2).

CREATE DATABASE catalog;
CREATE DATABASE orders;
CREATE DATABASE payment;
CREATE DATABASE inventory;

-- Not: Kullanıcı/parola ayrımı da servis başına yapılabilir. Lokal geliştirmede
-- sadelik için tek 'kervan' kullanıcısı tüm DB'lere sahiptir. Prod'da her servisin
-- kendi kısıtlı kullanıcısı olur (least privilege).
GRANT ALL PRIVILEGES ON DATABASE catalog   TO kervan;
GRANT ALL PRIVILEGES ON DATABASE orders     TO kervan;
GRANT ALL PRIVILEGES ON DATABASE payment    TO kervan;
GRANT ALL PRIVILEGES ON DATABASE inventory  TO kervan;
