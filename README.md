# SentinelPay

A high-throughput, distributed **double-entry ledger and EOD settlement engine** (Java 21 · Spring Boot · PostgreSQL · Redis · Kafka) designed to guarantee **effectively-once financial accounting** and **zero balance drift** under concurrent load.

It solves three distributed systems failure modes inside one cohesive architecture:

| Failure Mode | Solution |
| --- | --- |
| Concurrent double-spending | Redis atomic `SET NX EX` ingress locks + deterministic DB lock ordering (sorted UUIDs before `SELECT ... FOR UPDATE`) |
| Floating-point / balance drift | Immutable minor-unit (`BIGINT`) double-entry journaling (`∑ signed_amount_minor = 0`) |
| Dual-write inconsistencies | Transactional Outbox pattern relayed with `FOR UPDATE SKIP LOCKED` |

## Documentation

| Document | Contents |
| --- | --- |
| [`SPECIFICATION.md`](SPECIFICATION.md) | Full master specification: architecture planes, DDL, reference implementation, PRD, TRD, API contracts, ADRs, phased roadmap |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Phase-wise checklist: pre-flight decisions, file-level tasks, gate criteria, test matrix, risks |
| [`CLAUDE.md`](CLAUDE.md) / [`.cursorrules`](.cursorrules) | Non-negotiable engineering invariants & tech-stack constraints for AI coding agents |
| [`docs/MASTER_PROMPT.md`](docs/MASTER_PROMPT.md) | Phase-gated master execution prompt for AI coding agents |
| [`docs/adr/`](docs/adr/) | TDR-001 … TDR-005 technical decision records |

## Quick Start

```bash
# PostgreSQL 17, Redis 7.4, Kafka 3.9 (KRaft)
docker compose up -d --wait

# Phase 1 verification gate
./mvnw test -Dtest=SchemaMigrationIT
```

If the default host ports (`5432`, `6379`, `9092`) are already allocated, override them
(and point the application at the same ports):

```bash
SENTINELPAY_POSTGRES_PORT=5433 \
SENTINELPAY_REDIS_PORT=6380 \
SENTINELPAY_KAFKA_PORT=19092 \
docker compose up -d --wait

SENTINELPAY_DB_URL=jdbc:postgresql://localhost:5433/sentinelpay \
SENTINELPAY_REDIS_PORT=6380 \
SENTINELPAY_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./mvnw spring-boot:run
```

## Verification Gates (at a glance)

| Phase | Gate command | Proves |
| --- | --- | --- |
| 1 | `./mvnw test -Dtest=SchemaMigrationIT` | Flyway schema applies; `ledger_entries` is append-only |
| 2 | `./mvnw test -Dtest=ConcurrencyStressIT` | Zero deadlocks, zero drift, correct overdraft rejection |
| 3 | `./mvnw test -Dtest=OutboxResilienceIT` | At-least-once Kafka delivery survives broker outages |
| 4 | `./mvnw test -Dtest=ReconciliationEngineIT` | 100k-record EOD reconciliation with 100 injected anomalies < 5s |
| 5 | Gatling `LedgerChaosSimulation` | 2,500+ TPS at p99 < 25ms |

## Status

**Phase 1 complete** (2026-10-20) — Maven build, local PostgreSQL/Redis/Kafka topology, Flyway `V1`/`V2` migrations, and the `SchemaMigrationIT` gate (7 tests) are in place. No Phase 2 ledger service code yet; see [`SPECIFICATION.md`](SPECIFICATION.md) §11.
