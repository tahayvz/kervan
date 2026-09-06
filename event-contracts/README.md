# event-contracts

Servisler arasındaki **olay sözleşmeleri**. Bu modül çalışan bir uygulama değildir;
içinde Avro şemaları ve onlardan üretilen Java sınıfları vardır.

Karar gerekçesi: [ADR-0008](../docs/adr/0008-avro-schema-registry.md)

## Neden ayrı bir modül?

Bir olay, iki servisin üzerinde anlaştığı sözleşmedir. Şema, olayı yayınlayan servisin
içinde dursaydı, olayı tüketen servis onu okuyabilmek için yayınlayanın bütün kodunu
bağımlılık olarak çekerdi. Gevşek bağlı olması gereken iki servis derleme zamanında
birbirine kilitlenirdi. Şema ortada durunca her iki taraf da yalnızca sözleşmeye
bağımlı olur.

## Şemalar

| Dosya | Olay | Yayınlayan |
|---|---|---|
| `src/main/avro/OrderPlaced.avsc` | `OrderPlaced` | order-service |

Java sınıfları elle yazılmaz. `mvn generate-sources` şemadan üretir; tek doğru kaynak
`.avsc` dosyasıdır. Şemayı değiştirip sınıfı güncellemeyi unutmak mümkün değildir.

## Şema değişikliği kuralı

Uyumluluk modu **BACKWARD**: yeni şema, eski şemayla yazılmış veriyi okuyabilmelidir.

**Güvenli:**
- Varsayılanı olan alan eklemek (`{"name": "x", "type": ["null","string"], "default": null}`)
- `doc` metnini değiştirmek

**Yıkıcı:**
- Varsayılanı olmayan alan eklemek → eski veride o alanın karşılığı yoktur
- Alan silmek → hâlâ eski şemayla okuyan tüketici alanı dolduramaz
- Alanın adını ya da tipini değiştirmek

Bu kurallar `SchemaEvolutionTest` içinde test edilir. Uyumsuz bir değişiklik yaparsan
CI kırılır — Schema Registry'nin çalışan sistemde vereceği hatayı test zamanına
çekmiş oluruz.

## Neden `decimal`, `double` değil?

Para alanları Avro'nun `decimal` mantıksal tipiyle taşınır (`precision 19, scale 2`).
Kayan nokta (`double`) para hesabında yuvarlama hatası üretir; `0.1 + 0.2` tam olarak
`0.3` etmez. `decimal`, değeri ölçeklenmiş tam sayı olarak taşır; okuyan taraf aynı
`BigDecimal`'i geri alır. `OrderPlacedSerializationTest` bunu doğrular.

## Testler

```bash
mvn -pl event-contracts test
```

| Test | Neyi doğrular |
|---|---|
| `OrderPlacedSerializationTest` | Olay ikili biçime yazılıp aynı değerlerle geri okunuyor; tutar kuruşuna kadar korunuyor |
| `SchemaEvolutionTest` | Hangi şema değişikliğinin güvenli, hangisinin yıkıcı olduğu |
