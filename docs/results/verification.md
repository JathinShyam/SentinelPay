# SentinelPay verification record

**Run date:** final suite 2026-11-01; benchmark 2026-10-31 to 2026-11-01 (Asia/Kolkata)
**Runtime:** Temurin JDK 21.0.13, Spring Boot 3.5.16, packaged executable JAR
**Infrastructure:** PostgreSQL 17, Kafka 3.9.0 and Redis 7.4 in Docker; Testcontainers 2.0.3 for the suite
**Host:** Intel Core i5-9300H (4 physical / 8 logical cores), 15.8 GB RAM, ext4 on NVMe

The host is a shared laptop, not a dedicated benchmark machine. PostgreSQL durability was left at
`synchronous_commit=on` and `fsync=on` throughout.

## Full suite

```bash
./mvnw verify
```

Result: **BUILD SUCCESS — 35 tests, 0 failures, 0 errors, 0 skipped** in 55.3 s.

| Test class | Tests | Time | Phase / gate |
| --- | ---: | ---: | --- |
| `SchemaMigrationIT` | 8 | 2.4 s | 1 — Flyway, append-only trigger, D-1/D-2 constraints |
| `LedgerTransferIT` | 7 | 0.4 s | 2 — double entry and request validation |
| `ReversalAuditIT` | 5 | 14.0 s | 2 — compensating reversals, audit drift and cross-account balance proof |
| `LedgerInvariantIT` | 1 | 1.2 s | 2 — global balance/journal invariant after mixed concurrency |
| `ConcurrencyStressIT` | 2 | 1.1 s | 2 — overdraft race and deadlock freedom |
| `IdempotencyIT` | 5 | 0.2 s | 3 — Redis replay, 409 conflicts, 5xx lock release |
| `KafkaConsumerIT` | 2 | 1.9 s | 3 — at-least-once delivery and event dedup |
| `OutboxResilienceIT` | 1 | 1.5 s | 3 — broker pause/recovery |
| `OutboxDeadLetterIT` | 1 | 3.0 s | 3 — unreachable broker → `DEAD_LETTER` after one retry |
| `ReconciliationEngineIT` | 2 | 25.7 s | 4 — 100,000 records, 100 anomalies, **3,084 ms** engine time |
| `MetricsIT` | 1 | 0.1 s | 5 — required Micrometer families and tags |

The `ReversalAuditIT` runtime includes the intentionally broken reversal envelope used by
`IdempotencyIT`; its `500 INTERNAL_ERROR` is expected and proves that a server error releases the
Redis idempotency lock. It is not a ledger failure.

## Post-run integrity

After the load runs, the same global checks used by `LedgerInvariantIT` were replayed against the
benchmark database:

| Check | Result |
| --- | --- |
| Accounts where `balance_minor <> SUM(ledger_entries.signed_amount_minor)` | 0 |
| Transactions where `SUM(ledger_entries.signed_amount_minor) <> 0` | 0 |
| Outbox events stuck `PENDING` after the relay drained | 0 |

Load shedding therefore returned explicit `503 DEPENDENCY_UNAVAILABLE` responses; it did not leave
partial postings or drift.

## Phase 5 benchmark

### Official gate: 2,500 rps target

Command (defaults in `LedgerChaosSimulation`):

```bash
./mvnw gatling:test -Dgatling.simulationClass=com.sentinelpay.gatling.LedgerChaosSimulation
```

Profile: 60 s ramp from 1 to 2,500 users/s, then 120 s at 2,500 users/s, 90% transfers / 10% audits,
plus the duplicate-key and insufficient-funds branches.

Report: `target/gatling/ledgerchaossimulation-20261031091711825/index.html` (not committed).
Screenshot: [`gatling-official-gate.png`](gatling-official-gate.png) — the failing assertions and sustained-phase
counters are captured in the repository.

| Metric | Sustained phase | Gate | Result |
| --- | ---: | ---: | --- |
| Offered requests | 300,000 in 120 s | 2,500/s | offered |
| Successful requests | 115,486 | — | 962 successful transfers/s |
| Failed requests | 184,514 | < 0.1% | **61.5% failed** |
| Mean completion throughput | 1,587.3 rps | ≥ 2,500 rps | **FAIL** |
| p50 response time | 2,017 ms | — | — |
| p95 response time | 2,142 ms | — | — |
| p99 response time | 3,041 ms | < 25 ms | **FAIL** |
| Global failed requests | 57.24% | < 0.1% | **FAIL** |

The three error-path requests passed as designed: duplicate create `201`, replay `200`, insufficient
funds `422`. All 184,514 failed load requests were `503` responses caused by HikariCP connection
acquisition timeouts while the database was saturated. The assertion was **not** relaxed.

### Why the target fails on this host

PostgreSQL is the bottleneck, and its commits are bounded by durable storage. `pg_test_fsync -s 2`
inside the PostgreSQL container measured:

| Sync method | Ops/sec | Time per op |
| --- | ---: | ---: |
| `fdatasync` | 891 | 1,122 µs |
| `fsync` | 450 | 2,223 µs |
| `open_datasync` | 479 | 2,088 µs |
| `open_sync` | 218 | 4,588 µs |

A durable transfer commit needs at least one WAL sync. Even with group commit, the observed
~960 successful commits/s is consistent with this ceiling. At 2,500 offered requests/s the fixed
60-connection pool and 2 s acquisition timeout shed load instead of silently degrading durability.

Under no load, 20 sequential transfers measured p50 10 ms, p95 18 ms, max 35 ms. The p99 < 25 ms
target is therefore plausible only with substantially faster durable storage or a different
commit/batching design; it is not a tuning artifact.

### Sustained closed-loop capacity

A standalone closed-loop probe (10 concurrent HTTP/1.1 clients for 45 s, same transfer endpoint)
sustained **928.8 rps with zero errors**:

| Metric | Value |
| --- | ---: |
| Requests | 41,799 |
| Throughput | 928.8 rps |
| p50 | 7 ms |
| p95 | 16 ms |
| p99 | 73 ms |
| Max | 775 ms |
| HTTP 5xx | 0 |

This is the highest clean throughput observed on this host. It confirms the service is stable when
concurrency is bounded near the storage ceiling, but p99 is already about 3× the 25 ms target at
929 rps. A closed-loop client cannot offer 2,500 rps here; the open-model Gatling run is the
authoritative gate.

An open-model probe at 800 rps (20 s ramp, 60 s hold) also failed: 42,459 of 48,000 sustained
requests succeeded (**708 successful rps**), 5,541 returned `503` (10.3%), and p99 was 2,455 ms.
Bursty open-model concurrency exhausts the 60-connection pool before the database can drain it.

## Observability

All nine dashboard panels rendered live data in Grafana 11.3.0. Screenshot:
[`grafana-live.png`](grafana-live.png).

| Check | Result |
| --- | --- |
| Prometheus target `up{job="sentinelpay"}` | `1` |
| Datasource | Prometheus, uid `prometheus` |
| Dashboard | `SentinelPay`, uid `sentinelpay`, 9 panels |
| Transfer outcomes | `POSTED`, `IDEMPOTENT_REPLAY`, `INSUFFICIENT_FUNDS` series present |
| Transfer p99 (5 min rate, during load) | 0.298 s |
| Outbox pending gauge | `0` after drain |
| Outbox oldest-event lag | `0 s` after drain |
| Hikari connections | active/idle/pending series present; pending spike visible under overload |
| Reconciliation counter | `MISSING_INTERNAL` incremented by a controlled one-record run |

`MetricsIT` additionally asserts the required families and tags at `/actuator/prometheus`,
including `sentinelpay_ledger_lock_acquisition_duration_seconds` and
`sentinelpay_reconciliation_discrepancies_total{type="MISSING_INTERNAL"}`.

## Reproduction

```bash
# Infrastructure on the documented alternative ports
SENTINELPAY_POSTGRES_PORT=5433 docker compose up -d --wait

# Packaged application used for the benchmark
SENTINELPAY_DB_URL=jdbc:postgresql://localhost:5433/sentinelpay \
SENTINELPAY_DB_POOL_SIZE=60 \
java -Xms2g -Xmx4g -jar target/sentinelpay-0.0.1-SNAPSHOT.jar

# Full suite (real Testcontainers Postgres, Redis and Kafka)
./mvnw verify

# Official Phase 5 gate
./mvnw gatling:test -Dgatling.simulationClass=com.sentinelpay.gatling.LedgerChaosSimulation

# Below-capacity profile
SENTINELPAY_GATLING_TARGET_RPS=800 \
SENTINELPAY_GATLING_RAMP_SECONDS=20 \
SENTINELPAY_GATLING_SUSTAIN_SECONDS=60 \
./mvnw gatling:test -Dgatling.simulationClass=com.sentinelpay.gatling.LedgerChaosSimulation
```
