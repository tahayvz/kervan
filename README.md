# Kervan Commerce Platform

[![CI](https://github.com/tahayvz/kervan/actions/workflows/ci.yml/badge.svg)](https://github.com/tahayvz/kervan/actions/workflows/ci.yml)
[![CodeQL](https://github.com/tahayvz/kervan/actions/workflows/codeql.yml/badge.svg)](https://github.com/tahayvz/kervan/actions/workflows/codeql.yml)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> An event-driven e-commerce backend built as a set of Spring Boot microservices,
> using only open-source components.

A caravan (*kervan*) carried goods from station to station. Here an order travels the
same way — catalog → cart → order → payment → inventory → shipping — moving between
services as **events** rather than synchronous calls. The name describes the architecture.

---

## What this project is

Not a CRUD demo. It works through the problems that show up once a system is split into
services and has to stay correct anyway: **distributed consistency**, event-driven
communication, observability, resilience, and deployment.

Three rules the project holds itself to:

- Every technology choice is justified in writing — see [docs/TECH-RADAR.md](docs/TECH-RADAR.md)
- Every significant architectural decision is recorded as an ADR — see [docs/adr/](docs/adr/)
- Every commit explains what changed and why

Documentation under `docs/` is written in Turkish.

---

## Status — honest version

The project is built in phases, and each phase ships something that runs. The
architecture below is the target; the table says what actually exists today. Nothing is
marked done unless its code and tests are in this repository.

| Phase | Scope | Status |
|---|---|---|
| 0 | Mono-repo skeleton, ADRs, architecture docs, local infra compose | ✅ Done |
| 1 | **Catalog Service** — MongoDB, Mongock migrations, OpenAPI, Testcontainers | ✅ Done |
| 3a | **Order Service + Transactional Outbox → Kafka** | ✅ Done |
| 2a | **JWT security — OAuth2 resource server, roles, record-level ownership** | ✅ Done |
| 2b | **API Gateway (Spring Cloud Gateway)** — one front door, central authentication | ✅ Done |
| 3b | **Avro + Confluent Schema Registry** — versioned event contracts, compatibility enforced in tests | ✅ Done |
| 3c | **Debezium CDC — PostgreSQL WAL + outbox routing** | ✅ Done |
| 3d | **Debezium CDC — MongoDB change streams (Catalog)** | ✅ Done |
| 4a | **Saga message contracts + topic topology** | ✅ Done |
| 4b | **Inventory Service — stock reservation, idempotent consumer, compensation** | ✅ Done |
| 4c | **Payment Service — capture, refund, simulated provider behind a port** | ✅ Done |
| 4d | **Saga orchestrator — state machine, compensation, idempotent steps** | ✅ Done |
| 5 | Elasticsearch (search) + Redis (cache, locking) | Planned |
| 6 | OpenTelemetry + Prometheus + Grafana + Jaeger | Planned |
| 7 | Resilience4j — circuit breaker, retry, bulkhead, rate limiting | Planned |
| 8a | **CI — build, tests on real containers, image build, CodeQL** | ✅ Done |
| 9 | Kubernetes + Helm | Planned |

Full roadmap: [docs/ROADMAP.md](docs/ROADMAP.md)

---

## Architecture

Dependencies point inward. Each service owns its data; no service reaches into another
service's database. Services talk over REST when a caller needs an answer now, and over
Kafka when they do not.

```
                         ┌──────────────┐
                         │   Keycloak   │  OAuth2 / OIDC
                         └──────┬───────┘
                                │ JWT
                         ┌──────▼───────┐
   Client   ───────────▶ │ API Gateway  │  Spring Cloud Gateway
                         └──┬───┬───┬───┘
              ┌─────────────┘   │   └─────────────┐
        ┌─────▼─────┐    ┌──────▼──────┐   ┌──────▼──────┐
        │  Catalog  │    │    Order    │   │   Search    │
        │ (MongoDB) │    │ (Postgres)  │   │(Elasticsearch)
        └─────┬─────┘    └──────┬──────┘   └──────▲──────┘
              │ Outbox          │ Saga            │ indexing
              │                 ▼                 │
              │          ┌──────────────┐         │
              └─────────▶│ Apache Kafka │─────────┘
                         │ + Schema Reg │
              ┌──────────┴──────┬───────┴──────────┐
        ┌─────▼─────┐    ┌──────▼──────┐    ┌──────▼──────┐
        │ Inventory │    │   Payment   │    │Notification │
        └───────────┘    └─────────────┘    └─────────────┘
```

Two decisions worth calling out, both recorded as ADRs:

**Polyglot persistence.** Catalog runs on MongoDB because product attributes vary by
category; forcing that into a relational schema pushes you toward the EAV anti-pattern.
Order, payment and inventory run on PostgreSQL because money and stock need ACID
guarantees. ([ADR-0006](docs/adr/0006-polyglot-persistence-mongodb.md))

**Transactional Outbox over dual writes.** Writing to the database and publishing to
Kafka in the same operation is two writes with no shared transaction — a crash between
them loses the event or invents one. The outbox makes the event part of the same
database transaction, and a separate process ships it.
([ADR-0004](docs/adr/0004-transactional-outbox-debezium.md))

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## Implemented today

Both services use hexagonal architecture — the domain packages contain no Spring, JPA or
MongoDB types, so business rules are tested without a database.

### Catalog Service

```
domain/          product model, value objects, repository port
application/     use cases, commands, domain exceptions
infrastructure/  MongoDB adapter, Mongock migrations, OpenAPI config
web/             REST controllers, DTOs, RFC 7807 error handling
```

- **Authorization** — reads are public (a catalogue is a shop window); anything that
  *changes* the catalogue requires `ADMIN`. This was missing when the gateway went in,
  which left permanent deletes open to any authenticated customer — see
  [ADR-0007](docs/adr/0007-gateway-authenticates-services-authorize.md)
- **MongoDB + Mongock** — index migrations are versioned and run on startup
- **Testcontainers** — integration tests run against a real MongoDB, not an in-memory fake
- **RFC 7807** problem responses
- **Multi-stage Dockerfile** — build and runtime stages separated

Service documentation: [catalog-service/README.md](catalog-service/README.md)

### API Gateway — one front door

`api-gateway` (port 8000, because Keycloak already holds 8080) routes
`/api/v1/products/**` to catalog and `/api/v1/orders/**` to orders, and validates the
JWT before either service sees the request.

**It authenticates; it does not authorize.** That split is deliberate and written up in
[ADR-0007](docs/adr/0007-gateway-authenticates-services-authorize.md). Role checks and
record-level ownership stay in `order-service` for two reasons: a rule written in two
places drifts apart, and "is this order yours?" cannot be answered at the edge because
the answer is in the database. Catalog reads stay public, matching what the service
already did — a product list is a shop window.

The token is forwarded downstream unchanged and the services keep validating it
themselves. The gateway is a filter, not the only wall: a request that reaches a service
from inside the network still has to get past it.

Nine tests, each against two stub servers standing in for the real services:

| What it pins down | Why it matters |
|---|---|
| Products go to catalog, orders go to orders | A routing typo sends traffic to the wrong service and returns plausible-looking wrong data |
| An unknown path is routed nowhere | |
| The `Authorization` header survives the hop | Without it the service cannot identify the caller and ownership checks stop working |
| No token, foreign signature, or expired token → 401 **and the service receives nothing** | Asserting the 401 alone would not prove traffic was stopped at the edge, which is the point of having an edge |
| Product reads work without a token | The gateway must not quietly change behaviour the service already had |

### Order Service — outbox and security

Beyond the outbox described above, the service is an **OAuth2 resource server**. It does
not issue tokens; Keycloak does. It validates the signature, converts Keycloak's
`realm_access.roles` into Spring authorities, and enforces two layers:

- **Role level** — placing or reading an order requires `CUSTOMER` or `ADMIN`
- **Record level** — a customer may read only their **own** orders; an admin may read any

The second layer is the one role checks miss. A valid `CUSTOMER` token passes every role
gate and can still ask for someone else's order id; without an ownership check the
service hands it over. That rule lives in the domain (`Caller.owns`), not in a filter, so
it holds for any entry point, and it is asserted by tests rather than assumed.

**The order's owner comes from the token, not the request body.** `PlaceOrderRequest` has
no `customerId` field on purpose — if it did, anyone with a valid token could place orders
in another customer's name.

Eleven security tests cover the cases that matter: no token, a token signed by another
key, an expired token, a token with no roles, one customer reaching for another's order,
an admin reading any order, and which endpoints stay public.

### Order Service

Places orders and publishes `OrderPlaced` events — through an outbox, not directly.

```
POST /api/v1/orders
        │
        ▼
   OrderService.placeOrder()          ┌── one transaction ──┐
        ├─ write to orders            │                     │
        └─ write to outbox_messages   └─────────────────────┘
        │
        ▼  (background, polled)
   OutboxPublisher  ──▶  Kafka
```

Writing to the database and publishing to Kafka are two systems with no shared
transaction. A crash between them either loses the event or invents one, and no retry
can tell you which happened. Writing the event into the same transaction as the order
removes the question: both exist, or neither does.

The trade-off is at-least-once delivery — a crash after publishing but before marking
the row sends the event twice, so consumers must be idempotent.

Something still has to carry the row from the table to Kafka, and there are two ways to
do it. Both are in this repository. The in-process publisher polls the table; **Debezium**
reads the database's own write-ahead log instead — no query load, near-zero latency, and
the application never touches Kafka at all. Which one runs is a single setting
(`kervan.outbox.publisher.enabled`), so moving to CDC is a config change and is
reversible. Running both would send every event twice, so a test pins down that the
setting really removes the bean.

The connector's configuration is a JSON file, which the compiler cannot check — a
mistyped field name breaks nothing at build time and silently stops the flow. So the
integration test loads **that same file**, starts PostgreSQL, Kafka and Kafka Connect,
writes one row to the outbox table, and waits for the event on the topic. The
application is not running during that test; that is the point.

Nothing marks a row as delivered under CDC, so the table cannot be trimmed by delivery
status. A cleanup job runs hourly and deletes by age instead — but only under CDC. With
the in-process publisher it deletes only rows carrying a delivery stamp, because an old
row without one is an event that never got out. Which rule applies is read from the same
setting that picks the carrier, so the two cannot drift apart.

The same tool reads the catalogue out of **MongoDB change streams**, so both stores in
this polyglot setup feed the same log. It is deliberately not the same pattern, though.
The order side carries a domain event the application chose to publish, with its
contract written down in `event-contracts`. The catalogue side has no outbox: Debezium
reads the `products` collection directly and what travels is the document itself — a
projection of the data, whose first consumer will be the search index in phase 5.
Treating the two as one thing would turn an internal data model into a public contract.

- **PostgreSQL + Flyway** — schema is versioned; Hibernate runs with `ddl-auto: validate`
  and never touches the tables
- **Partial index** on unpublished rows only, so the publisher's query stays cheap as
  the table grows
- **Testcontainers** — the end-to-end test runs against real PostgreSQL *and* real Kafka,
  and asserts the event actually arrives

Service documentation: [order-service/README.md](order-service/README.md)

---

### Inventory Service

Runs the saga's first step: it reserves stock for an order, and releases the
reservation when payment fails. It has no REST API — this service talks in messages.

Stock is kept as two numbers, available and reserved, rather than one. With a single
number there would be no way to know how much to give back when the saga compensates;
reserving moves quantity between the two rather than destroying it.

**Idempotency comes from the work itself, not from a side table.** Delivery is
at-least-once, so the same command can arrive twice. The usual answer is a table of
processed message ids. Here the domain already has a natural key — an order has at most
one reservation — and that rule lives as a unique constraint. A second table would be a
copy of a rule that already exists, needing its own cleanup and its own correctness.

If one line of an order is short, **nothing** is reserved. Reserving the first and
stopping at the second would lock a product for a customer who is not getting the order.

When stock is short the exception does not escape. If it did, the transaction would roll
back and take the "stock was short" event with it, leaving the saga waiting forever for
an answer that no longer exists. Instead stock is left untouched and only the failure
event is written — both in the same commit.

Stock rows are locked with `SELECT ... FOR UPDATE`, always in SKU order. Two orders
holding overlapping products in different orders would each wait on the other; a fixed
order makes that deadlock structurally impossible rather than something to retry around.

Service documentation: [inventory-service/README.md](inventory-service/README.md)

---

### Payment Service

The saga's second step: it captures the order's amount and refunds it when a later step
fails. Same shape as the inventory service — commands in, answers out through the
outbox — with the same idempotency argument, which matters more here: reserving too
much stock is recoverable, charging a customer twice is not.

**There is no real payment provider, and the code does not pretend otherwise.** The
`PaymentGateway` port has a simulated implementation whose name says so. Its behaviour
is rule-based rather than random — amounts above a configured limit are declined — so
the saga's failure path can be exercised deterministically. A random simulator would
make tests fail occasionally and send the reader looking for the cause in the code,
when the thing under test is the saga, not the provider's mood. The port earns its keep
regardless: business rules are testable without the external system, and a real provider
would change exactly one class.

A declined payment is not an error, so the exception does not escape and the failure
event commits on its own. A failure *during a refund* is different and deliberately does
escape: the record must not be marked refunded when no money moved, so the transaction
rolls back and the command is retried.

Service documentation: [payment-service/README.md](payment-service/README.md)

---

### The saga

An order spans three services, and a distributed transaction across them does not scale.
The order service drives the flow instead and undoes what it has to when a step fails:
reserve stock, take payment, confirm — and on failure, release the stock and cancel.

The alternative is choreography, where each service triggers the next. There, the answer
to "why was this order cancelled" is spread across five services; in a flow whose job is
undoing things, that makes debugging nearly impossible.

**The saga starts inside the order's own transaction.** The first command is written to
the outbox alongside the order and its event. Sent as a separate step, a crash in between
would leave an order that exists while nothing behind it ever started — the customer sees
a confirmation and nothing happens.

**A failed payment does not cancel the order immediately.** The stock is released first
and the cancellation follows once compensation completes; otherwise the customer would be
told the order is cancelled while their stock is still held.

Delivery is at-least-once, so every step locks the saga row and asks the state machine
whether the transition is legal. An illegal one means the event has already been handled:
not an error, just ignored. Without the lock two events for the same order could be
processed side by side and send the same command twice.

Saga state lives in a table, not memory: steps can be minutes apart and a restart in
between would otherwise forget every order in flight.

The integration test drives both paths over real Kafka, with the other two services'
replies published by hand. **No test runs all three services together** — each has its own
end-to-end test and the contracts between them are pinned by `event-contracts`, but that
is not the same as proving the whole thing runs.

---

### Event contracts — Avro + Schema Registry

The event that leaves this service is a contract with services that are deployed
separately. They are never upgraded at the same moment, so for a while an old producer
and a new consumer run side by side. A format that cannot survive that turns every
deployment into an outage.

Events are serialised with **Avro**; the schemas live in one module,
[`event-contracts`](event-contracts/README.md), and the Java classes are generated from
them. **Confluent Schema Registry** stores each schema and rejects a new version that
would break the old one. Compatibility mode is `BACKWARD`: a new schema must still read
data written by the previous one, which is what lets the consumer be upgraded first.

```
OrderPlaced.avsc ──generate──▶ Java class ──serialise──▶ [0x00][schema id][body]
                                                                    │
        Schema Registry ◀── register ────────────────────────────────┘
```

The message carries the schema **id**, not the schema, so the payload stays small and the
consumer fetches the schema once. Money is carried as Avro `decimal`, not `double` — a
floating point kuruş is a wrong invoice — at the same scale as the database column, so a
three-decimal currency such as KWD fits without loss.

The registry's rule is also asserted at build time. `SchemaEvolutionTest` proves that
adding an optional field with a default is safe in both directions, and that adding a
field without a default, or removing a field, is not. An incompatible change fails CI
instead of a running consumer.

---

## Target stack

Everything is open source. Items not marked ✅ belong to later phases.

| Layer | Technology | |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.x | ✅ |
| Catalog persistence | MongoDB + Mongock | ✅ |
| Testing | JUnit 5, Testcontainers, AssertJ | ✅ |
| Containers | Docker, Docker Compose | ✅ |
| Transactional persistence | PostgreSQL + Flyway | ✅ |
| Async messaging | Apache Kafka | ✅ |
| Transactional Outbox | own implementation | ✅ |
| Schema management | Confluent Schema Registry + Avro | ✅ |
| Change data capture | Debezium (PostgreSQL WAL + MongoDB change streams) | ✅ |
| Saga orchestration | own implementation | ✅ |
| Search | Elasticsearch | planned |
| Cache / locking / rate limiting | Redis | planned |
| API gateway | Spring Cloud Gateway | ✅ |
| Identity | Keycloak (OAuth2 / OIDC / JWT) | ✅ |
| Resilience | Resilience4j | planned |
| Observability | OpenTelemetry, Prometheus, Grafana, Jaeger, Loki | planned |
| Orchestration | Kubernetes + Helm | planned |
| CI | GitHub Actions + CodeQL | ✅ |

Rationale for each choice, including the alternatives that were rejected:
[docs/TECH-RADAR.md](docs/TECH-RADAR.md)

---

## Running it

Requires Java 21, Maven 3.9+, Docker and Docker Compose.

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

```bash
mvn test     # 109 tests: 21 catalog + 79 order + 9 gateway
```

```bash
mvn -pl catalog-service spring-boot:run   # http://localhost:8081
mvn -pl order-service   spring-boot:run   # http://localhost:8082
mvn -pl api-gateway     spring-boot:run   # http://localhost:8000  (front door)
```

Order endpoints require a bearer token. The local Keycloak realm (`kervan`) ships with
two users for manual exploration — `musteri` / `musteri` (CUSTOMER) and `yonetici` /
`yonetici` (ADMIN). Development credentials only; that realm file never leaves Docker
Compose.

```bash
curl -s -d 'client_id=kervan-cli' -d 'username=musteri' -d 'password=musteri'      -d 'grant_type=password'      http://localhost:8080/realms/kervan/protocol/openid-connect/token
```

API documentation is **closed by default** — it hands an attacker a map of the endpoints,
field names and validation rules. To browse it locally:

```bash
KERVAN_EXPOSE_API_DOCS=true mvn -pl order-service spring-boot:run
```

Integration tests start their own MongoDB, PostgreSQL and Kafka containers, so Docker
must be running. They mint their own signed tokens, so Keycloak is not needed to run
them.

---

## License

MIT — see [LICENSE](LICENSE).
