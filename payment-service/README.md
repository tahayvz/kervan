# payment-service

Saga'nın ödeme adımını yürütür: siparişin tutarını tahsil eder, sonraki bir adım
başarısız olursa iade eder.

Karar gerekçeleri: [ADR-0005](../docs/adr/0005-saga-orchestration.md) (Saga),
[ADR-0009](../docs/adr/0009-saga-message-topology.md) (mesaj topolojisi).

## Akış

```
kervan.payments.commands
        │
        ├─ ProcessPayment ──▶ tahsil et ──┐
        └─ RefundPayment  ──▶ iade et   ──┤   tek transaction
                                          │
                              outbox_messages'a yaz
                                          │
                                          ▼ (Debezium)
                              kervan.payments.events
```

## Gerçek bir ödeme sağlayıcısı yok — ve öyleymiş gibi durmuyor

Bu proje bir ürün değil, teknolojileri deneyimlemek için kurulmuş çalışan bir sistem
(bkz. kök [README](../README.md) → "What this project is not"). Ödeme burada bir
**saga adımıdır**; amaç para tahsil etmek değil, başarısız olabilen bir adımın
telafisini doğru kurmaktır.

Projede bağlanılacak bir sağlayıcı bulunmuyor. `PaymentGateway` portunun uygulaması
(`SimulatedPaymentGateway`) bir **taklit**tir ve adı bunu söyler.

Davranışı **kurala** bağlı, rastgele değil: belirlenen sınırın üstündeki tutar
reddedilir. Rastgele olsaydı testler ara sıra kırılır ve kırılma sebebi kodda
aranırdı; oysa doğrulanmak istenen şey saga'nın doğruluğu, sağlayıcının kaprisi değil.

Port yine de var, çünkü asıl mesele orada: dış sistem çağrısı bir port arkasında
durunca iş kuralları o sistem olmadan test edilebilir ve gerçek sağlayıcı geldiğinde
değişecek tek şey bir sınıf olur.

## İdempotentlik: burada daha da önemli

Teslimat en az bir kezdir. Fazla stok ayırmak düzeltilebilir bir hatadır;
**müşteriden iki kez para çekmek değildir.**

Ayrı bir "işlenmiş mesajlar" tablosu yok: işin doğal anahtarı zaten var — bir
siparişin en fazla bir ödemesi olur — ve bu kural `payments.order_id` üzerinde
benzersizlik kısıtı olarak duruyor. İki katman: önce mevcut ödeme aranır (normal
durum), yarış hâlinde kısıt devreye girer.

Erken çıkış bir hızlandırma değildir. Kaldırıldığında tekrar gelen komut kısıta
takılıp istisna fırlatır, dinleyici offset'i ilerletmez ve aynı mesaj sonsuza kadar
döner — o partition'daki her şeyle birlikte. (inventory-service'te bu deneyle
görüldü.)

## Reddedilen ödeme bir hata değildir

Sağlayıcı reddettiğinde istisna dışarı taşmaz. Taşsaydı transaction geri alınır ve
"ödeme alınamadı" olayı da silinirdi; saga cevap beklerken asılı kalırdı. Tahsilat
kaydı hiç yazılmaz, yalnızca başarısızlık olayı yazılır — ikisi aynı commit'te.

**İade sırasında sağlayıcı hata verirse durum farklı:** orada istisna bilerek dışarı
taşar. Kayıt "iade edildi" olarak işaretlenmemelidir, çünkü para gönderilmemiştir;
transaction geri alınır ve komut tekrar denenir.

## Tıkanan mesaj kuyruğu kilitlemesin

Kalıcı hata veren bir mesaj birkaç kez, aralığı açılarak denenir; sonra
`<konu>.DLT` konusuna taşınır ve akış devam eder. Tanınmayan bir komut tipi
**yeniden denenmeden** oraya gider: beklemekle tanınır hâle gelmez.

**DLT boş kalmadığı sürece izlenmelidir.**

## Katmanlar

```
domain/          Payment, Money, PaymentStatus + portlar (PaymentGateway dâhil)
application/     PaymentProcessingService
infrastructure/  JPA adaptörleri, Kafka dinleyicisi, Avro yayıncı, outbox, taklit sağlayıcı
```

`Money` tipi order-service'te de var ama paylaşılmıyor: ikisi ayrı bounded context.
Ortak kütüphaneye taşımak iki servisi birbirine bağlardı — mikroservis sınırında küçük
bir tekrar, paylaşılan bir bağımlılıktan ucuzdur.

## Çalıştırma

```bash
docker compose -f ../infra/docker/docker-compose.yml up -d postgres kafka schema-registry connect
```

```bash
mvn -pl payment-service spring-boot:run
```

Servis `http://localhost:8086` (yalnızca actuator; dışa açık REST API'si yok).

Konektörü kaydet:

```bash
curl -X POST -H 'Content-Type: application/json' \
     --data @../infra/docker/debezium/payment-outbox-connector.json \
     http://localhost:8083/connectors
```

## Testler

```bash
mvn -pl payment-service test
```

16 test: domain birim testleri (ödeme yaşam döngüsü, para birimi ölçeği), use-case
testleri (idempotentlik, reddedilme, iade, iade sırasında sağlayıcı hatası) ve gerçek
PostgreSQL + Kafka container'larına karşı çalışan uçtan uca testler.
