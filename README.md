# Kervan Commerce Platform

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

The project is built in phases, and each phase ships something that runs. **Phase 1 is
complete; the rest is designed but not yet implemented.** The architecture below is the
target; the table says what actually exists today.

| Phase | Scope | Status |
|---|---|---|
| 0 | Mono-repo skeleton, ADRs, architecture docs, local infra compose | ✅ Done |
| 1 | **Catalog Service** — MongoDB, Mongock migrations, OpenAPI, Testcontainers | ✅ Done |
| 2 | API Gateway + Keycloak (OAuth2 / OIDC) | Planned |
| 3 | Kafka + Avro + Schema Registry + Transactional Outbox + Debezium | Planned |
| 4 | Order / Payment / Inventory + Saga orchestration | Planned |
| 5 | Elasticsearch (search) + Redis (cache, locking) | Planned |
| 6 | OpenTelemetry + Prometheus + Grafana + Jaeger | Planned |
| 7 | Resilience4j — circuit breaker, retry, bulkhead, rate limiting | Planned |
| 8 | CI/CD with GitHub Actions | Planned |
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

## Implemented today: Catalog Service

Hexagonal architecture — the domain has no Spring or MongoDB types in it.

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

---

## Target stack

Everything is open source. Items not marked ✅ belong to later phases.

| Layer | Technology | |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3.x | ✅ |
| Catalog persistence | MongoDB + Mongock | ✅ |
| Testing | JUnit 5, Testcontainers, AssertJ | ✅ |
| Containers | Docker, Docker Compose | ✅ |
| Transactional persistence | PostgreSQL + Flyway | planned |
| Async messaging | Apache Kafka | planned |
| Schema management | Confluent Schema Registry + Avro | planned |
| Change data capture | Debezium | planned |
| Distributed consistency | Transactional Outbox + Saga | planned |
| Search | Elasticsearch | planned |
| Cache / locking / rate limiting | Redis | planned |
| API gateway | Spring Cloud Gateway | planned |
| Identity | Keycloak (OAuth2 / OIDC / JWT) | planned |
| Resilience | Resilience4j | planned |
| Observability | OpenTelemetry, Prometheus, Grafana, Jaeger, Loki | planned |
| Orchestration | Kubernetes + Helm | planned |
| CI/CD | GitHub Actions | planned |

Rationale for each choice, including the alternatives that were rejected:
[docs/TECH-RADAR.md](docs/TECH-RADAR.md)

---

## Running it

Requires Java 21, Maven 3.9+, Docker and Docker Compose.

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

```bash
mvn -pl catalog-service test
```

```bash
mvn -pl catalog-service spring-boot:run
```

The service starts on `http://localhost:8081`; OpenAPI UI at `/swagger-ui.html`.

Integration tests start their own MongoDB container, so Docker must be running.

---

## License

MIT — see [LICENSE](LICENSE).
