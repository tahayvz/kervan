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
| 2b | API Gateway (Spring Cloud Gateway) | Planned |
| 3b | Avro + Schema Registry + Debezium CDC | Planned |
| 4 | Order / Payment / Inventory + Saga orchestration | Planned |
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

- **MongoDB + Mongock** — index migrations are versioned and run on startup
- **Testcontainers** — integration tests run against a real MongoDB, not an in-memory fake
- **RFC 7807** problem responses
- **Multi-stage Dockerfile** — build and runtime stages separated

Service documentation: [catalog-service/README.md](catalog-service/README.md)

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

- **PostgreSQL + Flyway** — schema is versioned; Hibernate runs with `ddl-auto: validate`
  and never touches the tables
- **Partial index** on unpublished rows only, so the publisher's query stays cheap as
  the table grows
- **Testcontainers** — the end-to-end test runs against real PostgreSQL *and* real Kafka,
  and asserts the event actually arrives

Service documentation: [order-service/README.md](order-service/README.md)

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
| Schema management | Confluent Schema Registry + Avro | planned |
| Change data capture | Debezium | planned |
| Saga orchestration | | planned |
| Search | Elasticsearch | planned |
| Cache / locking / rate limiting | Redis | planned |
| API gateway | Spring Cloud Gateway | planned |
| Identity | Keycloak (OAuth2 / OIDC / JWT) | planned |
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
mvn test     # 92 tests: 13 catalog + 79 order
```

```bash
mvn -pl catalog-service spring-boot:run   # http://localhost:8081
mvn -pl order-service   spring-boot:run   # http://localhost:8082
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
