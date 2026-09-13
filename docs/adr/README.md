# Architecture Decision Records (ADR)

Bir **ADR**, önemli bir mimari kararı; **bağlamı**, **verilen kararı**, **değerlendirilen
alternatifleri** ve **sonuçlarını** ile kalıcı olarak kaydeder.

**Neden tutuyoruz?** 6 ay sonra "biz bunu neden böyle yapmıştık?" sorusunun cevabı
kaybolmasın diye. ADR'ler teknik kararları savunulabilir ve izlenebilir kılar;
kararı veren kişi ekipten ayrıldığında gerekçe onunla birlikte gitmez.

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
- [ADR-0006](0006-polyglot-persistence-mongodb.md) — Polyglot persistence: Catalog MongoDB, çekirdek PostgreSQL
- [ADR-0007](0007-gateway-authenticates-services-authorize.md) — Ağ geçidi kimlik doğrular, yetkiyi servisler denetler
- [ADR-0008](0008-avro-schema-registry.md) — Olay biçimi: Avro + Schema Registry (JSON değil)
- [ADR-0009](0009-saga-message-topology.md) — Saga mesaj topolojisi: komut/olay ayrımı, konu başına çok tip
- [ADR-0010](0010-search-read-model.md) — Arama için ayrı okuma modeli (CQRS)
- [ADR-0011](0011-redis-cache-and-rate-limit.md) — Redis: önbellek ve hız sınırlama (dağıtık kilit değil)
- [ADR-0012](0012-observability-instrumentation.md) — İzleme kod içinden (Java ajanı değil)
- [ADR-0013](0013-trace-context-across-outbox.md) — İzleme bağlamı outbox satırında taşınır
- [ADR-0014](0014-metrics-pull-and-management-port.md) — Metrikler çekilir, actuator ayrı portta
- [ADR-0015](0015-logs-to-a-file-a-collector-ships-them.md) — Log dosyaya yazılır, taşımayı toplayıcı yapar
- [ADR-0016](0016-where-circuit-breakers-go.md) — Devre kesici nereye konur ve nereye konmaz
- [ADR-0017](0017-kubernetes-helm-single-chart.md) — Altı servis tek Helm paketinde, altyapı dışarıda
- [ADR-0018](0018-cd-verify-the-deployment-not-deploy-it.md) — CD dağıtımı doğrular, otomatik dağıtım yapmaz
- [ADR-0019](0019-stock-enters-by-receipt.md) — Stok makbuzla girer, mutlak atamayla değil
- [ADR-0020](0020-saga-tested-across-three-real-services.md) — Saga üç gerçek servisle test edilir, CDC taşıması taklit edilir
- [ADR-0021](0021-stock-corrections-are-reasoned-and-attributed.md) — Stok düzeltmesi gerekçeli ve sahiplidir

## Yeni ADR nasıl yazılır
`NNNN-kisa-baslik.md` adıyla, `_template.md`'yi kopyalayarak.
