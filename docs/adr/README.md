# Architecture Decision Records (ADR)

Bir **ADR**, önemli bir mimari kararı; **bağlamı**, **verilen kararı**, **değerlendirilen
alternatifleri** ve **sonuçlarını** ile kalıcı olarak kaydeder.

**Neden tutuyoruz?** 6 ay sonra "biz bunu neden böyle yapmıştık?" sorusunun cevabı
kaybolmasın diye. Bir lead için ADR'ler, teknik kararları savunulabilir ve
izlenebilir kılan en güçlü araçtır. Mülakatta "neden Kafka, neden RabbitMQ değil?"
sorusuna işaret edebileceğin yazılı bir kanıttır.

## Durumlar
- **Proposed** — önerildi, tartışılıyor
- **Accepted** — kabul edildi, uygulanıyor
- **Superseded by ADR-XXXX** — daha yeni bir kararla değiştirildi
- **Deprecated** — artık geçerli değil

## Kayıtlar
- [ADR-0001](0001-microservices-over-monolith.md) — Mikroservis vs. Modüler Monolit
- [ADR-0002](0002-monorepo-maven-multimodule.md) — Mono-repo + Maven multi-module
- [ADR-0003](0003-kafka-over-rabbitmq.md) — Event omurgası: Kafka (RabbitMQ değil)
- [ADR-0004](0004-transactional-outbox-debezium.md) — Dual-write: Outbox + Debezium
- [ADR-0005](0005-saga-orchestration.md) — Dağıtık tutarlılık: Saga (orchestration)

## Yeni ADR nasıl yazılır
`NNNN-kisa-baslik.md` adıyla, `_template.md`'yi kopyalayarak.
