# SentinelPay verification record

**Run date:** final suite 2026-11-05; benchmark 2026-10-31 to 2026-11-05 (Asia/Kolkata)
**Runtime:** Temurin JDK 21.0.13, Spring Boot 3.5.16, packaged executable JAR
**Infrastructure:** PostgreSQL 17, Kafka 3.9.0 and Redis 7.4 in Docker; Testcontainers 2.0.3 for the suite
**Host:** Intel Core i5-9300H (4 physical / 8 logical cores), 15.8 GB RAM, ext4 on NVMe

The host is a shared laptop, not a dedicated benchmark machine. PostgreSQL durability was left at
`synchronous_commit=on` and `fsync=on` throughout.

## Full suite

```bash
./mvnw verify
```

Result: **BUILD SUCCESS — 36 tests, 0 failures, 0 errors, 0 skipped** in 53.4 s.

| Test class | Tests | Time | Phase / gate |
| --- | ---: | ---: | --- |
| `SchemaMigrationIT` | 9 | 2.4 s | 1 — Flyway V1–V3, append-only journal and transaction triggers, D-1/D-2 constraints |
| `LedgerTransferIT` | 7 | 0.3 s | 2 — double entry and request validation |
| `ReversalAuditIT` | 5 | 13.7 s | 2 — compensating reversals, audit drift and cross-account balance proof |
| `LedgerInvariantIT` | 1 | 0.7 s | 2 — global balance/journal invariant after mixed concurrency |
| `ConcurrencyStressIT` | 2 | 0.9 s | 2 — overdraft race and deadlock freedom |
| `IdempotencyIT` | 5 | 0.2 s | 3 — Redis replay, 409 conflicts, 5xx lock release |
| `KafkaConsumerIT` | 2 | 1.9 s | 3 — at-least-once delivery and event dedup |
| `OutboxResilienceIT` | 1 | 1.4 s | 3 — broker pause/recovery |
| `OutboxDeadLetterIT` | 1 | 2.9 s | 3 — unreachable broker → `DEAD_LETTER` after one retry |
| `ReconciliationEngineIT` | 2 | 24.9 s | 4 — 100,000 records, 100 anomalies, **2,594 ms** engine time |
| `MetricsIT` | 1 | 0.1 s | 5 — required Micrometer families and tags |

The `ReversalAuditIT` runtime includes the intentionally broken reversal envelope used by
`IdempotencyIT`; its `500 INTERNAL_ERROR` is expected and proves that a server error releases the
Redis idempotency lock. It is not a ledger failure.

## Clean-clone quick start

Verified on 2026-11-01 from a fresh clone of `c3ffd65` with a clean working tree and no reused
Compose volumes. The documented port overrides were used because `5432` and `3000` are occupied
on the host.

```bash
SENTINELPAY_POSTGRES_PORT=5433 \
SENTINELPAY_REDIS_PORT=6380 \
SENTINELPAY_KAFKA_PORT=19092 \
SENTINELPAY_GRAFANA_PORT=3001 \
docker compose up -d --wait

./mvnw -q -DskipTests package

SENTINELPAY_DB_URL=jdbc:postgresql://localhost:5433/sentinelpay \
SENTINELPAY_REDIS_PORT=6380 \
SENTINELPAY_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./mvnw spring-boot:run
```

| Check | Result |
| --- | --- |
| Compose services | PostgreSQL, Redis, Kafka, Prometheus and Grafana all healthy |
| Clean package | `BUILD SUCCESS`; 68 MB executable JAR |
| Actuator health | `UP` |
| Account creation | Two funded/unfunded INR accounts created |
| Transfer | `201 POSTED`, 25,000 minor units; balances 975,000 and 25,000 |
| Idempotent replay | `200` with `Idempotency-Replayed: true` |
| Audit | `driftMinor: 0`, `transactionsBalanced: true` |
| Async delivery | 5 outbox events `PROCESSED`; 5 `processed_kafka_events` rows |
| Observability | Prometheus target `up = 1`; Grafana health `ok`; outbox pending gauge `0` |

The clone and its Compose volumes were removed after the check.

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

### 4-vCPU container-limited run

Run on 2026-11-05 with the unchanged simulation and an explicit 4.0 vCPU container budget:

| Container | CPU limit |
| --- | ---: |
| Application (`eclipse-temurin:21-jre-jammy`) | 2.50 |
| PostgreSQL 17 | 1.00 |
| Kafka 3.9 | 0.25 |
| Redis 7.4 | 0.25 |
| **Total** | **4.00** |

Gatling ran on the host outside that budget, as the load generator. The data-plane limits are in
[`docker/benchmark/docker-compose.cpu-limits.yml`](../../docker/benchmark/docker-compose.cpu-limits.yml);
the app used `--network host --cpus 2.5` with the same JVM and pool settings as the reproduction
commands below. The JVM reported an effective CPU count of 3 under that quota. The host remained
shared with unrelated workload, so the quotas cap this stack but do not reserve physical cores.

| Metric | Sustained phase | Gate | Result |
| --- | ---: | ---: | --- |
| Offered requests | 300,000 in 120 s | 2,500/s | offered |
| Successful requests | 51,702 | — | **431 successful/s** |
| Failed requests | 248,298 | < 0.1% | **82.8% failed** |
| Mean completion throughput | 1,604.3 rps | ≥ 2,500 rps | **FAIL** |
| p50 response time | 2,035 ms | — | — |
| p95 response time | 2,824 ms | — | — |
| p99 response time | 3,224 ms | < 25 ms | **FAIL** |
| Global failed requests | 78.97% | < 0.1% | **FAIL** |

The three error-path requests again passed. All failed load requests were `503` responses; there
were no client-side connection errors in the retained run. Post-run checks found **0** accounts
with balance/journal drift, **0** unbalanced transactions, and **0** pending outbox events.

Screenshot: [`gatling-cgroup-4vcpu.png`](gatling-cgroup-4vcpu.png). Report (ignored):
`target/gatling/ledgerchaossimulation-20261105183201006/index.html`.

An initial allocation (app 1.5 vCPU, PostgreSQL 2.0) starved the app enough to hold roughly 28,000
connections, exhaust the load generator's ephemeral ports, and produce `ConnectException` failures.
That run was discarded. An intermediate run also recorded 1,011 client connection timeouts and was
discarded; the reported run started at the final 2.5-vCPU quota and had none.

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
The 4-vCPU run also failed (431 successful/s, p99 3,224 ms); with 1.0 vCPU for PostgreSQL and 2.5
for the app, CPU contention compounds the storage ceiling rather than changing the conclusion.

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

### 4-vCPU container-limited reproduction

```bash
# Data plane: 1.0 (Postgres) + 0.25 (Kafka) + 0.25 (Redis) vCPU
SENTINELPAY_POSTGRES_PORT=5433 \
SENTINELPAY_REDIS_PORT=6380 \
SENTINELPAY_KAFKA_PORT=19092 \
docker compose -f docker-compose.yml \
  -f docker/benchmark/docker-compose.cpu-limits.yml \
  up -d --wait postgres redis kafka

# Application: 2.5 vCPU, bringing the total to 4.0
docker run -d --name sentinelpay-bench-app --network host --cpus 2.5 \
  -e SENTINELPAY_DB_URL=jdbc:postgresql://localhost:5433/sentinelpay \
  -e SENTINELPAY_DB_POOL_SIZE=60 \
  -e SENTINELPAY_REDIS_PORT=6380 \
  -e SENTINELPAY_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
  -v "$PWD/target/sentinelpay-0.0.1-SNAPSHOT.jar:/app.jar:ro" \
  eclipse-temurin:21-jre-jammy java -Xms1g -Xmx2g -jar /app.jar

# Unchanged Phase 5 gate
./mvnw gatling:test -Dgatling.simulationClass=com.sentinelpay.gatling.LedgerChaosSimulation
```
