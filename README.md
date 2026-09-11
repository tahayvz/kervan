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

## What this project is not

**It does not sell anything.** There is no real payment provider, no real customers, no
real catalogue. This is a working system built to exercise a set of technologies and the
problems they exist to solve — not a product looking for users.

That distinction is stated here rather than left to be discovered, because it changes how
some of the code should be read:

- The payment gateway is a **simulator** behind a port, and its class name says so. Its
  behaviour is rule-based rather than random so the saga's failure path can be exercised
  on purpose.
- Product and customer data is whatever gets seeded; nothing here is anyone's real order.
- Decisions are made for the case a real system would face, and the reasoning is written
  down even where the demo itself would survive a lazier choice — that reasoning is the
  point of the exercise.

What is real is everything between those edges: the messaging, the transactions, the
compensation, the schemas, the failure handling. Those run against real PostgreSQL, real
MongoDB, real Kafka and real Debezium in the tests, not against mocks.

The last phase closes the loop: seed synthetic customers and orders, put load through the
system, and watch it from the outside — traces, metrics, and dashboards. A technology you
have only wired up is not one you have understood; the point is to see it under pressure.

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
| 5a | **Search Service — Elasticsearch read model fed by catalogue CDC** | ✅ Done |
| 5b | **Redis — cache and rate limiting** (no distributed lock; ADR-0011 says why) | ✅ Done |
| 6a | **Distributed tracing — OpenTelemetry + Jaeger, and the trace survives the outbox** | ✅ Done |
| 6b | Metrics and logs — Prometheus, Grafana, Loki | Planned |
| 7 | Resilience4j — circuit breaker, retry, bulkhead, rate limiting | Planned |
| 8a | **CI — build, tests on real containers, image build, CodeQL** | ✅ Done |
| 9 | Kubernetes + Helm | Planned |
| 10 | Synthetic load — seeded data, sustained traffic, watching it from outside | Planned |

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

### Search Service

An order's catalogue is queried far more often than it is written, and the questions are
different: "Nike, electronics, 1000–2000 TL, 'headphones' in the name", with counts
beside each filter. MongoDB is shaped for writing that catalogue; asking it to do all of
that as well means one workload slowing the other.

So search gets its own read model. This service writes nothing — it listens to the
catalogue's change stream and keeps an Elasticsearch copy shaped for querying. The
catalogue service does not know search exists; adding another read model later would not
require touching it.

The index is derived data. If it is lost, nothing is lost: the consumer reads from the
beginning of the stream and rebuilds it. That is the recovery plan, not a backup.

**Field types are declared rather than inferred.** Elasticsearch will happily create an
index on first write and guess that every string is analysed text — which silently breaks
two things at once: facet counts stop working, and filtering on "New Balance" starts
matching anything containing "New". The annotations only take effect if *we* create the
index, so it is created before the first document.

Redelivery is handled by the index itself rather than by bookkeeping. Each document is
written with the source document's own version as an external version, so a repeated
event changes nothing and a late one cannot overwrite newer data.

The cost is stated rather than hidden: search is **eventually consistent** with the
catalogue, and the stream it consumes is a raw change record with no versioned contract
— rename a field in the catalogue and this service breaks. Both were accepted knowingly
(ADR-0010), and the translation lives in one class so the breakage has one place to
happen.

There is a test for the whole chain — Mongo, Debezium, Kafka, index — because the other
tests hand-write the change event, which only proves my assumption about its shape. That
shape has no contract, so the only way to know is to read what Debezium actually emits.

Service documentation: [search-service/README.md](search-service/README.md)

---

### Redis: cache and rate limiting — and no lock

Catalogue reads repeat, so product lookups go through a Redis cache. It is a decorator
around the repository rather than an annotation, so the application layer never learns
the cache exists. Writes **evict** rather than update — what was saved and what the
database returns are not always identical, and deleting guarantees the next read is
correct. Search is deliberately not cached: the query space is too wide to hit, and
there is no way to know which cached results a write invalidates.

The open endpoints needed protection, so the gateway rate-limits with a token bucket
whose counters live in Redis — in memory, each replica would enforce its own limit and
the real limit would multiply by the replica count. Authenticated requests are keyed by
user, anonymous ones by IP: IP alone would make one office share a quota, user alone
would leave the open endpoints unprotected.

Adding Redis immediately broke something worth keeping: the gateway's health endpoint
started reporting 503, because Boot includes Redis in health by default. In production
that means a brief Redis blip takes the entire front door out of the load balancer — the
rate limiter's datastore killing the thing it protects. Redis is now excluded from
gateway health, and when it is unreachable requests pass through unthrottled. Failing
open is a real risk, but a temporary one; failing closed is a certain outage. Both
behaviours have tests.

**There is no distributed lock, and that is a decision rather than an omission.** Every
place that needed mutual exclusion already has it from the database: `FOR UPDATE` in SKU
order for stock, a locked saga row per order, unique constraints for idempotency,
`SKIP LOCKED` for the outbox. Those guarantees are in the same transaction as the data.
A Redis lock would create a second answer to "who holds it", with no rule for which
answer wins. ADR-0011 records this, and the order to reach for if one is ever needed.

---

### Tracing: following an order that nobody hands over

Seven services, and one order touches three of them. When a request is slow or stalls,
no single service's log holds the answer, because no single service sees the whole
chain. Tracing does: every step is a span, the whole chain is a trace.

Instrumentation is **in code** — Micrometer's tracing bridge over OpenTelemetry — not
the `-javaagent` agent. The agent has wider automatic coverage, but it only exists at
runtime, so nothing about it can be verified in CI, and in this repository a behaviour
without a test is a behaviour that does not exist. Spans go to an OpenTelemetry
Collector rather than straight to Jaeger, so the applications know one address and the
backend behind it can change without touching them. ADR-0012 records the trade.

**The hard part is that the chain has a step with no application code in it.** An order
event is never written to Kafka by the service: it is written to the outbox table in the
order's own transaction, and Debezium later reads the database's change log and
publishes it. Debezium has no request and no thread of the caller — it cannot know what
context to carry. Left alone, every service starts its own trace, and one order shows up
in Jaeger as four unconnected fragments: exactly the question tracing exists to answer,
unanswered.

So the context travels **with the data**. The outbox row carries a `trace_parent`
column, filled in by the outbox adapter — the business code never learns tracing exists.
Debezium's `EventRouter` copies that column into the Kafka `traceparent` header, and
consumers resume the same trace. One order, one chain: HTTP request → stock → payment →
completion. ADR-0013 has the reasoning, including why the context does not go inside the
Avro payload.

Two details are easy to get wrong and both are pinned by tests. Kafka Connect's default
header converter is JSON, which would write the value **in quotes**; a W3C parser
discards a quoted header silently — no exception, no log, just a broken chain — so the
integration test asserts the header is byte-for-byte equal, not merely present. And the
outbox producer has Spring's automatic observation switched **off**: it runs from a
scheduled job whose own trace has nothing to do with the order, and if it were on, the
library would overwrite the correct context with a plausible-looking wrong one.

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
| Search | Elasticsearch | ✅ |
| Cache / rate limiting | Redis | ✅ |
| API gateway | Spring Cloud Gateway | ✅ |
| Identity | Keycloak (OAuth2 / OIDC / JWT) | ✅ |
| Resilience | Resilience4j | planned |
| Tracing | OpenTelemetry (Micrometer bridge) + Jaeger | ✅ |
| Metrics / logs | Prometheus, Grafana, Loki | planned |
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
