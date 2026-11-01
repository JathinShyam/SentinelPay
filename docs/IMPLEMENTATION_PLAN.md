# SentinelPay — Phase-Wise Checklist & Implementation Plan

> **Companion to:** [`SPECIFICATION.md`](../SPECIFICATION.md) · **Status:** planning baseline (2026-10-29)
> **Build protocol:** execute one phase at a time. Tick every box in the phase, run its verification gate, record the result, and only then start the next phase. Never weaken an invariant to make a gate pass.

---

## 0. Progress Dashboard

| Phase | Scope | Verification gate | Status |
| --- | --- | --- | --- |
| 0 | Documentation bootstrap | Docs reviewed | ✅ Complete (2026-10-17) |
| 1 | Foundation, Docker topology, immutable schema | `./mvnw test -Dtest=SchemaMigrationIT` | ✅ Complete (2026-10-31) |
| 2 | Double-entry engine, reversals, audit, concurrency proof | `./mvnw test -Dtest=ConcurrencyStressIT` | ✅ Complete (2026-10-31) |
| 3 | Redis idempotency, outbox relay, Kafka consumer dedup | `./mvnw test -Dtest=OutboxResilienceIT` | ✅ Complete (2026-10-31) |
| 4 | Virtual-thread EOD reconciliation | `./mvnw test -Dtest=ReconciliationEngineIT` | ✅ Complete (2026-10-31) |
| 5 | Metrics, Grafana, Gatling, portfolio README | Gatling 2,500+ TPS, p99 < 25 ms | 🟡 Code/observability complete; load gate failed as documented (2026-11-01) |

**Definition of done (every phase):**

- [ ] `./mvnw -q -DskipTests package` succeeds from a clean checkout.
- [x] Phase gate passes against real Testcontainers infrastructure (no mocked Postgres/Redis/Kafka).
- [x] All six engineering laws hold (see [`CLAUDE.md`](../CLAUDE.md)).
- [x] No floating-point monetary types, no Kafka calls inside request threads, no ledger mutations.
- [x] New configuration documented in `application.yml` and this plan updated.
- [ ] Committed as a reviewable phase-sized change with a green gate.

---

## 1. Pre-Flight Decisions & Environment

### 1.1 Decisions to lock before coding

| ID | Decision | Recommendation | Why it matters |
| --- | --- | --- | --- |
| **D-1** | `transactions` is declared append-only, but FR-03 says "mark the original transaction as `REVERSED`". | **Do not mutate the original row.** Add `reversal_of UUID UNIQUE REFERENCES transactions(transaction_id)` to `transactions`; insert the reversal envelope with `tx_type='REVERSAL'` and derive the effective status (`REVERSED` if a reversal row exists). | Honours Law 5 literally, keeps full audit history, and makes double-reversal idempotent via the unique constraint. |
| **D-2** | The DDL's global `CHECK (balance_minor >= 0)` makes double-entry funding of user wallets impossible (the funding account must go negative). | Add `account_type VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (account_type IN ('USER','SYSTEM'))` and change the constraint to `CHECK (account_type = 'SYSTEM' OR balance_minor >= 0)`. Seed `SYSTEM_RESERVE` (positive) and `SYSTEM_EQUITY` (negative) with a `GENESIS`/`DEPOSIT` transaction. | User wallets are funded through real double-entry transfers instead of raw balance edits. |
| **D-3** | The source material names `V2__init_reconciliation_schema.sql` but does not include its DDL. | Define it in Phase 1 as `reconciliation_runs`, `reconciliation_discrepancies`, and `processed_kafka_events` (sketch in §4.1). | Phase 3/4 need the tables; leaving the schema undefined invites hallucinated DDL. |
| **D-4** | Reconciliation result volume: persisting `MATCHED` rows would mean ~100,000 inserts and threaten the 5-second gate. | Persist **only anomalies** (`AMOUNT_MISMATCH`, `MISSING_INTERNAL`, `MISSING_EXTERNAL`); store `MATCHED` as a counter on the run row. | Keeps the hot path fast while still producing an auditable count. |
| **D-5** | The reference relay loops over 100 events with a 3-second send timeout each; a broker outage can stall the batch for minutes. | Abort the batch on the first send failure, apply backoff to that event and the remaining unclaimed-in-memory batch, and use a configurable send timeout (default 1,000 ms in tests, 3,000 ms in prod). | Makes retry behaviour bounded and `OutboxResilienceIT` deterministic; at-least-once semantics are unchanged. |
| **D-6** | Runtime baseline: the overview says Spring Boot 3.4, the TRD pins 3.5.x or 4.1.1. | Use **Spring Boot 3.5.x** (latest patch) + Java 21 + Testcontainers 2.0.3. Record the exact patch in `pom.xml`. | One authoritative baseline avoids mixed-version surprises. |
| **D-7** | Missing idempotency headers are not in the published error taxonomy. | Add `400 IDEMPOTENCY_KEY_REQUIRED` for transfer/reversal endpoints; keep the published mapping for all other cases. | Edge filter needs a deterministic response before touching the DB. |

> **Action:** If any decision is rejected, record the alternative in `docs/adr/` before the affected phase starts. Once `V1` has been applied to a shared database, never edit it again — add `V3+` migrations instead.

### 1.2 Tooling prerequisites

- [x] JDK 21 (Temurin) available; `java -version` reports 21.
- [x] Docker Engine + Compose v2 running; Testcontainers can start Postgres, Redis, Kafka.
- [x] Maven 3.9+ available once to generate `mvnw`, then use the wrapper.
- [x] ~8 GB free RAM for the full Testcontainers suite (Postgres + Redis + Kafka + app context).
- [x] Ports free: `5432`, `6379`, `9092`, `8080`, `9090` (Prometheus), `3000` (Grafana). Documented Compose overrides cover local conflicts; this run used `5433` for Postgres and `3001` for Grafana.

### 1.3 Global engineering conventions

- **Money:** `long` / `BIGINT` minor units only. JSON fields end in `Minor`. No `double`, `float`, `BigDecimal`.
- **Package layout:** package-by-feature (`ledger`, `outbox`, `reconciliation`, `common`) exactly as in `SPECIFICATION.md` §9.1.
- **Persistence:** `JdbcClient` / `NamedParameterJdbcTemplate`; no JPA/Hibernate, no `ddl-auto`. Flyway owns schema.
- **Domain types:** Java records for DTOs/domain read models; enums for `AccountStatus`, `TxType`, `TxStatus`, `Direction`, `ReconciliationType`.
- **Transactions:** all balance mutations inside one `@Transactional` method; outbox insert inside the same transaction.
- **Locking:** every multi-account lock path sorts account UUIDs ascending; no ad-hoc `FOR UPDATE` elsewhere.
- **Time:** UTC everywhere; `Instant` in Java, `TIMESTAMPTZ` in Postgres; `business_date` is an ISO `LocalDate`.
- **Errors:** RFC 7807 `ProblemDetail` with `code` property from the taxonomy; no stack traces/DB messages leaked.
- **Tests:** `*IT.java` run against real containers; use Awaitility for asynchronous assertions; fail on `DeadlockLoserDataAccessException`.
- **Naming:** gate test class names exactly as specified; use `-Dtest=<Class>` to select them with Surefire.
- **Logging:** structured key/value logs; never log account balances at INFO for every transfer (noise), log transaction IDs and outcomes.

---

## 2. Phase 1 — Foundation, Docker Topology & Immutable Schema

**Objective:** a runnable Spring Boot skeleton with pinned dependencies, local infrastructure, Flyway migrations, and proof that the ledger is append-only.

### 2.1 Repository & build scaffold

- [x] `git init`; add `.gitignore` (`target/`, `.idea/`, `*.iml`, `.env`, `*.log`, `.DS_Store`), `.editorconfig`, and commit the documentation baseline.
- [x] Generate the Maven wrapper: `mvn wrapper:wrapper -Dmaven=3.9.9`; commit `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`.
- [x] Create `pom.xml`:
  - [x] Parent `spring-boot-starter-parent` 3.5.x; `<java.version>21</java.version>`.
  - [x] Runtime: `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-data-redis`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-kafka`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `micrometer-registry-prometheus`, `commons-csv`, `jackson-datatype-jsr310`.
  - [x] Test: `spring-boot-starter-test`, `spring-kafka-test`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`, `org.testcontainers:kafka`, `spring-boot-testcontainers`, `awaitility`.
  - [x] Phase 5 placeholders: `gatling-maven-plugin`, Gatling `test` dependency (add now to avoid a later build reshuffle).
  - [x] Pin Testcontainers **2.0.3** via `<testcontainers.version>`.
- [x] Add Maven compiler config `--release 21` and Surefire `-Dtest` compatibility for `*IT` classes.

### 2.2 Docker topology

- [x] `docker-compose.yml` with named volumes, one bridge network, healthchecks:
  - [x] `postgres:17-alpine` (fallback `18-alpine` if image stable): `POSTGRES_DB=sentinelpay`, dev user/password, `pg_isready` healthcheck.
  - [x] `redis:7.4-alpine` with `--appendonly yes`; `redis-cli ping` healthcheck.
  - [x] Kafka **KRaft single node** (`apache/kafka:3.9.0`): broker + controller in one container, `KAFKA_NODE_ID=1`, `KAFKA_PROCESS_ROLES=broker,controller`, listeners on `9092`, 6 partitions; healthcheck via `kafka-broker-api-versions`.
  - [x] Prometheus + Grafana (used in Phase 5; add now with provisioning directories).
  - [x] Optional `app` service was not added; the README runs the wrapper/JAR against the infra-only Compose stack.
- [x] Create `docker/prometheus/prometheus.yml`, `docker/grafana/provisioning/{datasources,dashboards}/…` placeholders.
- [x] Smoke-test: `docker compose up -d`, verify all healthchecks green, then `docker compose down -v`.

### 2.3 Application configuration

- [x] `src/main/resources/application.yml`:
  - [x] Datasource: `jdbc:postgresql://localhost:5432/sentinelpay`, Hikari `maximum-pool-size: 50`, `connection-timeout: 2000`, `minimum-idle: 10`.
  - [x] Flyway enabled, `locations: classpath:db/migration`.
  - [x] Redis host/port + Lettuce pool.
  - [x] Kafka producer: `acks=all`, `enable.idempotence=true`, `retries=Integer.MAX_VALUE`, `compression.type=lz4`, String serializers; consumer: batch ack, `enable-auto-commit=false`, `auto-offset-reset=earliest`.
  - [x] `spring.threads.virtual.enabled: true`.
  - [x] Actuator: expose `health,info,metrics,prometheus`; health probes enabled.
  - [x] Custom properties: `sentinelpay.outbox.poll-interval-ms`, `batch-size`, `send-timeout-ms`, `max-retries`; `sentinelpay.idempotency.in-flight-ttl`, `completed-ttl`; `sentinelpay.reconciliation.chunk-size`, `max-concurrency`.
  - [x] Upload limits for reconciliation files (`spring.servlet.multipart.max-file-size: 100MB`).
- [x] Add `OutboxProperties`, `IdempotencyProperties`, `ReconciliationProperties` as `@ConfigurationProperties` records.
- [x] `SentinelPayApplication` with `@SpringBootApplication`, `@EnableScheduling`, `@ConfigurationPropertiesScan`.

### 2.4 Flyway migrations

- [x] `V1__init_ledger_schema.sql` — base DDL from the spec, **plus D-1/D-2 amendments**:
  - [x] `accounts.account_type` + conditional non-negative check + `idx_accounts_type`.
  - [x] `transactions.reversal_of UUID UNIQUE REFERENCES transactions(transaction_id)` + index.
  - [x] Keep trigger `trg_immutable_ledger` and indexes exactly as specified.
- [x] `V2__init_reconciliation_schema.sql`:
  - [x] `reconciliation_runs(run_id, file_name, business_date, status, total_records, matched_count, amount_mismatch_count, missing_internal_count, missing_external_count, error_message, started_at, completed_at)`.
  - [x] `reconciliation_discrepancies(discrepancy_id, run_id FK, type, external_ref, transaction_id, expected_amount_minor, actual_amount_minor, currency, details JSONB, created_at)` with `CHECK type IN ('MATCHED','AMOUNT_MISMATCH','MISSING_INTERNAL','MISSING_EXTERNAL')`.
  - [x] `processed_kafka_events(event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`.
  - [x] Indexes: `reconciliation_discrepancies(run_id, type)`, `reconciliation_runs(business_date DESC)`.
- [x] Verify migrations never insert/update ledger data manually.

### 2.5 Test infrastructure & schema gate

- [x] `AbstractIntegrationTest`:
  - [x] Static singleton `PostgreSQLContainer`, Kafka 3.9 container with a fixed host port and explicit localhost advertised listener, Redis `GenericContainer`.
  - [x] Explicit `DynamicPropertySource` wiring for all three containers; no infrastructure mocks. A fixed Kafka host port avoids Testcontainers 2.x advertised-listener surprises.
  - [x] `@SpringBootTest(webEnvironment = RANDOM_PORT)`; containers start as static singletons and are reused across the suite.
- [x] `SchemaMigrationIT`:
  - [x] Context starts; `flyway_schema_history` contains V1 and V2 with `success = true`.
  - [x] Insert a valid `DEBIT`/`CREDIT` pair; both accepted.
  - [x] `UPDATE ledger_entries` throws a PostgreSQL/Spring `DataAccessException` containing "append-only".
  - [x] `DELETE FROM ledger_entries` throws the same.
  - [x] Wrong-sign entry (`direction='DEBIT'`, positive amount) is rejected by `chk_direction_sign`.
  - [x] `SELECT indexdef ...` confirms `idx_outbox_pending` is partial (`WHERE status = 'PENDING'`).
  - [ ] Confirm the `transactions` row cannot be deleted (add a trigger in V3 if we decide to enforce it; currently only the trigger on entries exists). **Not enforced:** journal immutability is the tested invariant; an unreferenced transaction row could still be deleted.

### 2.6 Phase 1 gate

```bash
./mvnw test -Dtest=SchemaMigrationIT
```

**Exit criteria:** gate green; `docker compose up -d` yields healthy Postgres/Redis/Kafka; `./mvnw -DskipTests package` succeeds; no application logic beyond configuration/skeleton exists yet.

**Watch-outs:** Flyway checksum immutability (edit V1 only before first shared apply); Kafka KRaft advertised-listener mistakes (`localhost:9092` vs container hostname); Testcontainers 2.x package renames — isolate container config in one base class so API churn is contained.

---

## 3. Phase 2 — Core Double-Entry Engine, Reversals & Concurrency Proof

**Objective:** a working REST ledger: create/fund accounts, transfer money with deadlock-free double-entry posting, reverse transactions, and prove balances via audit under concurrency.

### 3.1 Common error layer

- [x] Exception types with contextual fields: `AccountNotFoundException`, `InsufficientFundsException`, `CurrencyMismatchException`, `AccountNotActiveException`, `InvalidAmountException`, `TransactionNotFoundException`, `ReversalNotAllowedException`, `IdempotencyConflictException`.
- [x] `ApiErrorCode` enum and `GlobalExceptionHandler` (`@RestControllerAdvice`) producing `ProblemDetail` with `code`, `title`, `status`, `detail`, `instance`, plus domain properties (e.g., `availableMinor`, `requestedMinor`).
- [x] Map validation failures (`MethodArgumentNotValidException`, malformed UUID, missing header) to 400 `INVALID_AMOUNT` / `INVALID_REQUEST`.
- [x] Map frozen/closed accounts to **422 `ACCOUNT_NOT_ACTIVE`** (FR-01 extension; keep published 422 `INSUFFICIENT_FUNDS` and `CURRENCY_MISMATCH` unchanged).

### 3.2 Domain model & repositories

- [x] Enums: `AccountStatus`, `AccountType`, `TxType`, `TxStatus`, `Direction`.
- [x] Records: `Account` (with `withBalance(long)`), `TransactionEntity`, `LedgerEntry`, `OutboxEvent`; no setters.
- [x] `AccountRepository` (JdbcClient):
  - [x] `findById`, `findByIdForUpdate` (`SELECT ... WHERE account_id = :id FOR UPDATE`), `insert`, `updateBalance` (`version = version + 1`, `updated_at = NOW()`), `findByOwner`.
- [x] `TransactionRepository`:
  - [x] `insert`, `findById`, `findByIdempotencyKey`, `findByIdForUpdate`, `findReversalOf(originalId)`, `findByIds`.
- [x] `LedgerEntryRepository`:
  - [x] `batchInsert`, `findByTransactionId`, `sumSignedAmountByAccountId`, `sumSignedAmountByTransactionId`, `findRunningBalanceForTransaction`.
- [x] `OutboxRepository.insert`.
- [x] Row mappers map `TIMESTAMPTZ` → `Instant`, `CHAR(3)` → trimmed `String`.

### 3.3 System funding & account lifecycle

- [x] `SystemClearingSeeder` (`ApplicationRunner`, idempotent, fixed UUIDs):
  - [x] Create `SYSTEM_EQUITY` (`account_type='SYSTEM'`, allowed negative) and `SYSTEM_RESERVE` (`account_type='SYSTEM'`).
  - [x] If no `GENESIS` transaction exists, post a `DEPOSIT` transaction: debit equity `-X`, credit reserve `+X`, idempotency key `genesis-v1`; seed `X = 1_000_000_000_000` minor units.
- [x] `AccountService.createAccount(ownerId, currency, initialDepositMinor)`:
  - [x] Validate ISO-4217 currency (`INR`, `USD`, `EUR` supported) and non-negative deposit.
  - [x] Insert account with `balance_minor = 0`, `version = 0`.
  - [x] If deposit > 0, post an internal `DEPOSIT` transfer from `SYSTEM_RESERVE` to the new account (same double-entry path), idempotency key `deposit:{accountId}`.
  - [x] Return account view; never write a balance directly outside the transfer service.
- [x] `AccountController`:
  - [x] `POST /api/v1/accounts` → 201 with `accountId`, `ownerId`, `currency`, `balanceMinor`, `status`.
  - [x] `GET /api/v1/accounts/{accountId}` → 200/404.
  - [x] `GET /api/v1/owners/{ownerId}/accounts` (optional convenience).

### 3.4 Transfer, reversal & audit services

- [x] `LedgerTransferService.executeTransfer(TransferRequest)`:
  - [x] DB idempotency backstop: `insertIfAbsent`; a conflicting insert returns the stored result (200 replay).
  - [x] Sort `sourceAccountId` / `destinationAccountId` ascending, lock both with `FOR UPDATE` in that order.
  - [x] Validate `ACTIVE` status on both accounts, currency equality (source, destination, request), positive `amountMinor`, distinct accounts.
  - [x] Check source balance **after locks**; update both materialized balances with version increment.
  - [x] Insert `transactions` (`POSTED`), two `ledger_entries` (`DEBIT -amount`, `CREDIT +amount`, running balances), and `outbox_events` (`TransferPostedEvent`, `aggregate_id = transactionId`) in the same transaction.
  - [x] Return `TransferResult(transactionId, balancesAfter, createdAt)` for the controller.
- [x] `ReversalService.reverse(originalTxId, idempotencyKey)`:
  - [x] Lock original transaction row; reject non-`POSTED`/already-reversed originals.
  - [x] Check for existing reversal first (idempotent replay) and rely on `transactions.reversal_of` UNIQUE as the race backstop.
  - [x] Lock the two original accounts in sorted order; create inverse entries (`DEBIT` original destination, `CREDIT` original source) with current running balances.
  - [x] Insert `REVERSAL` transaction with `reversal_of = originalTxId`; insert outbox `TransferReversedEvent`.
  - [x] Derived status: `REVERSED` when a linked reversal exists; original row remains untouched.
  - [x] If the original destination lacks funds (spent), return 422 `INSUFFICIENT_FUNDS` — documented limitation of non-negative wallets.
- [x] `LedgerAuditService.auditAccount(accountId)`:
  - [x] `ledgerBalanceMinor = COALESCE(SUM(signed_amount_minor), 0)` for the account.
  - [x] Compare to `accounts.balance_minor`; compute `driftMinor = materialized - ledger`.
  - [x] Verify every linked transaction's entries sum to zero.
  - [x] Return `{accountId, materializedBalanceMinor, ledgerBalanceMinor, driftMinor, entryCount, transactionsBalanced, checkedAt}`; 200 even when drift ≠ 0 (certificate reports the failure).

### 3.5 API surface & DTOs

- [x] `POST /api/v1/transfers` — `Idempotency-Key` required; 201 new, 200 replay; response fields exactly as in the spec (`transactionId`, `idempotencyKey`, `status`, `amountMinor`, `currency`, balances after, `createdAt`).
- [x] `POST /api/v1/transactions/{transactionId}/reverse` — `Idempotency-Key` required; 201 new reversal, 200 replay.
- [x] `GET /api/v1/accounts/{accountId}/audit` — audit certificate.
- [x] DTO validation: `@NotNull` UUIDs, `@Positive amountMinor`, `@Pattern("[A-Z]{3}") currency`; reject `amountMinor <= 0` and identical accounts in the request record constructor as a second line of defence.
- [x] No `BigDecimal` in any DTO; JSON serialization of `long` amounts exactly as integers.

### 3.6 Tests

- [x] `LedgerTransferIT`: happy path; exact two entries summing to zero; running balances; 404 unknown account; 422 frozen/closed, insufficient funds, currency mismatch; DB idempotency replay returns same `transactionId`.
- [x] `ReversalAuditIT`: reversal creates inverse entries; original derives `REVERSED`; double reversal is idempotent; audit reports `driftMinor = 0`; unknown transaction 404.
- [x] `LedgerInvariantIT`: after a mixed workload, for every account `balance_minor = SUM(ledger_entries.signed_amount_minor)`; every transaction sums to zero.
- [x] `ConcurrencyStressIT` (gate):
  - [x] **Test 1 — Overdraft race:** fund A with `10_000`; fire 50 concurrent transfers of `1_000` A→B; require exactly 10 successes, 40 `INSUFFICIENT_FUNDS`, final A balance `0`, B balance `10_000`.
  - [x] **Test 2 — Bidirectional deadlock proof:** fund A and B; fire 100 concurrent A→B and 100 concurrent B→A transfers of `100`; require all 200 to succeed, **zero** `DeadlockLoserDataAccessException`, net balances unchanged, audit `driftMinor = 0`.
  - [x] Use `ExecutorService`/virtual threads + `CountDownLatch` to align starts; assert via real HTTP or the transactional service against real Postgres.

### 3.7 Phase 2 gate

```bash
./mvnw test -Dtest=ConcurrencyStressIT
```

**Exit criteria:** gate green; account creation is funded via the genesis double-entry path; audit returns `driftMinor = 0`; no direct balance writes outside the ledger service; no `KafkaTemplate` usage in request-path code.

**Watch-outs:** balance updates must happen *after* locks; one `@Transactional` boundary for balances + entries + outbox; do not retry deadlocks in the test (a retry could hide a lock-ordering bug); `chk_non_negative_balance` must not block system accounts.

---

## 4. Phase 3 — Two-Tier Idempotency, Outbox Relay & Kafka Consumer

**Objective:** duplicate HTTP retries never double-debit at the edge; every committed transaction reaches Kafka exactly-once *effectively* even through a broker outage.

### 4.1 Schema (already applied in Phase 1)

- [x] `processed_kafka_events(event_id PK, processed_at)` exists.
- [x] Outbox partial index and statuses verified.
- [x] Topic `ledger.transactions.v1` created with 6 partitions, replication factor 1 in dev, key = `aggregate_id`.

### 4.2 Redis ingress idempotency

- [x] `IdempotencyProperties`: header name `Idempotency-Key`, in-flight TTL `30s`, completed TTL `24h`, max body `1MB`.
- [x] `CachedBodyHttpServletRequest` + `ContentCachingResponseWrapper` so the filter can hash the request body once and replay the response.
- [x] `RedisIdempotencyFilter` (`OncePerRequestFilter`, high precedence, before DB/security transaction):
  - [x] Scope: mutating methods under `/api/v1/` (transfers, reversals; account creation if header present).
  - [x] Require the header for transfer/reversal → 400 `IDEMPOTENCY_KEY_REQUIRED` when absent; enforce 16–128 chars.
  - [x] Fingerprint = SHA-256(`method + path + raw body`); store `{state, fingerprint, httpStatus, contentType, body}` as JSON.
  - [x] Acquire atomically: `SET idemp:{key} <record> NX EX 30`; only the winner proceeds.
  - [x] Loser branch:
    - [x] `IN_PROGRESS` + same fingerprint → 409 `IDEMPOTENCY_IN_FLIGHT` + `Retry-After: 1`.
    - [x] `IN_PROGRESS` + different fingerprint → 409 `IDEMPOTENCY_IN_FLIGHT` (payload mismatch).
    - [x] `COMPLETED` + same fingerprint → replay cached headers/body (a cached `201` is replayed as `200` per FR-02), add `Idempotency-Replayed: true`.
    - [x] `COMPLETED` + different fingerprint → 409.
  - [x] On completion: cache 2xx and deterministic 4xx responses for 24h; release the key (`DEL`) on 5xx so the client can retry.
  - [x] Ensure the filter never holds a DB connection and never caches `/actuator/**`.
- [x] `IdempotencyIT`:
  - [x] Sequential replay returns the same `transactionId`, one ledger transaction only.
  - [x] 20 concurrent identical requests → exactly one `201`, others `200` replay or `409`; exactly one debit.
  - [x] Same key + different body → 409.
  - [x] Failed request (e.g., insufficient funds) is cached deterministically; 5xx path releases the key.
  - [x] Redis key TTLs and `Idempotency-Replayed` header asserted.

### 4.3 Transactional outbox relay

- [x] `OutboxProperties`: `poll-interval-ms=200`, `batch-size=100`, `send-timeout-ms` (1000 test / 3000 prod), `max-retries=5`.
- [x] `OutboxRelayWorker`:
  - [x] `@Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-interval-ms:200}")`.
  - [x] Claim in one transaction: `SELECT ... WHERE status='PENDING' AND next_attempt_at <= NOW() ORDER BY created_at ASC LIMIT :batch FOR UPDATE SKIP LOCKED`.
  - [x] Publish with `KafkaTemplate.send(topic, aggregateId.toString(), payload).get(timeout)` to preserve per-transaction ordering.
  - [x] On success: `UPDATE ... SET status='PROCESSED', processed_at=NOW() WHERE event_id=:id AND status='PENDING'`.
  - [x] On failure (D-5): mark the failed event and all remaining claimed events `retry_count + 1`, `next_attempt_at = NOW() + retry_base_seconds * 2^(retry_count-1)` seconds (minimum 1s), break the batch; at `retry_count >= 5` → `DEAD_LETTER`.
  - [x] Never call Kafka from a request thread; relay runs only in the scheduler.
- [x] `KafkaTopicConfig`: `NewTopic` bean (`ledger.transactions.v1`, 6 partitions, 1 replica) for local/test profiles.
- [x] `SettlementKafkaConsumer`:
  - [x] `@KafkaListener(topics = "ledger.transactions.v1", groupId = "sentinelpay-settlement")`.
  - [x] Parse JSON to `TransferPostedEvent`; insert `processed_kafka_events` with `ON CONFLICT (event_id) DO NOTHING`.
  - [x] Only execute the side effect when the insert affected a row; `@Transactional` listener; duplicates are no-ops.
  - [x] Error handler with bounded retries + dead-letter topic, or log-and-DLT policy documented.
- [x] `KafkaConsumerIT`: publish duplicate `event_id`s; assert one `processed_kafka_events` row and one side effect.

### 4.4 Tests & gate

- [x] `OutboxResilienceIT`:
  - [x] Pause the Kafka container (Docker pause via `DockerClient`; fallback Toxiproxy if pause proves flaky).
  - [x] Execute 20 transfers; assert 20 `PENDING` outbox rows and at least one row with `retry_count > 0` (Awaitility, short send timeout).
  - [x] Unpause; assert all 20 events reach the consumer (`processed_kafka_events` count = 20) and all outbox rows become `PROCESSED` within **5 seconds**.
  - [x] Assert no event is lost and duplicate deliveries are deduplicated.
- [x] `OutboxDeadLetterIT`: configure `max-retries=1` and a real Kafka producer pointed at an unreachable broker; assert `DEAD_LETTER` and `retry_count = 1`. Synchronous metadata failures are converted into bounded retries before dead-lettering.
- [x] `IdempotencyIT` green.

```bash
./mvnw test -Dtest=OutboxResilienceIT
```

**Exit criteria:** gate green; no Kafka sends in HTTP/transaction paths; Redis filter proven under concurrency; consumer dedup proven; dead-letter path auditable.

**Watch-outs:** holding a DB transaction open while waiting on Kafka (accepted for the relay, keep batches bounded); response-wrapper forgetting `copyBodyToResponse()` truncates bodies; Kafka container pause may be unsupported on some Docker backends; never ack a Kafka record before the dedup insert commits.

---

## 5. Phase 4 — Java 21 Virtual-Thread EOD Reconciliation

**Objective:** ingest 100,000 clearing records, reconcile them against internal transactions with bounded virtual-thread concurrency, and isolate 100 injected anomalies in under 5 seconds.

### 5.1 File format & fixtures

- [x] Define the clearing record contract (CSV and NDJSON):
  `external_ref, transaction_id, amount_minor, currency, status, settled_at`
- [x] `ClearingRecord` record + validation (UUID, positive amount, ISO currency, status).
- [x] `ClearingFileParser`:
  - [x] Streaming reader (no full-file load), chunk size 1,000, header validation, line-numbered errors.
  - [x] CSV via Commons CSV; NDJSON via Jackson `ObjectMapper.readValues`.
  - [x] Extension/content-type detection on upload.
- [x] `SyntheticClearingFileGenerator` (test utility):
  - [x] 100,000 lines → 99,925 exact matches, 50 `AMOUNT_MISMATCH`, 25 `MISSING_INTERNAL`.
  - [x] Internal fixture: 100,000 transactions = those 99,975 + 25 `MISSING_EXTERNAL` (not present in file).
  - [x] Deterministic seed for reproducibility; writes to a temp file.

### 5.2 Reconciliation engine

- [x] `ReconciliationRunRepository` + `DiscrepancyRepository` (JdbcClient, batch insert).
- [x] `VirtualThreadReconciliationEngine.run(Path file, LocalDate businessDate)`:
  - [x] Insert `RUNNING` run row.
  - [x] Open `ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()` in try-with-resources.
  - [x] `Semaphore(50)` bounds concurrent DB lookups to protect Hikari; acquire/release around each chunk query in `finally`.
  - [x] For each chunk: `SELECT ... FROM transactions WHERE transaction_id = ANY(:ids)`; compare `amount_minor`, `currency`, `status` (only `POSTED` counts as matchable).
  - [x] Classify `MATCHED` (counter only) / `AMOUNT_MISMATCH` / `MISSING_INTERNAL`; add seen internal IDs to a concurrent set.
  - [x] Await all futures; propagate the first failure and mark run `FAILED` with `error_message`.
  - [x] Stream internal `POSTED` transaction IDs for `business_date` and diff against the seen set → `MISSING_EXTERNAL` rows.
  - [x] Persist anomalies in one batch; update run counts/status/`completed_at`.
  - [x] Never hold a single DB transaction across the whole run; chunk queries are read-only and short.
- [x] Endpoint `POST /api/v1/reconciliation/run` (multipart file + `businessDate`): save to temp, run synchronously, return `{runId, status, counts, durationMs}`.
- [x] `GET /api/v1/reconciliation/runs/{runId}` → summary; `GET .../discrepancies?type=` → paginated anomalies.
- [x] Configuration: `chunk-size=1000`, `max-concurrency=50`.

### 5.3 Tests & gate

- [x] `ReconciliationEngineIT`:
  - [x] Bulk-seed 100,000 internal transactions via JDBC batch (`COPY`/batching; not part of the measured window).
  - [x] Generate the 100,000-line file with exactly 100 anomalies.
  - [x] Warm up with a 1,000-line file, then measure the full run.
  - [x] Assert counts: 99,925 matched, 50 amount mismatch, 25 missing internal, 25 missing external; exactly 100 discrepancy rows.
  - [x] Assert duration **< 5,000 ms**; log measured duration for the README.
- [x] `ReconciliationParserIT` (optional): covered by `ReconciliationEngineIT.malformedClearingFileFailsTheRunWithLineNumber`; malformed input reports the line number and fails cleanly without a completed run.
- [x] Confirm `sentinelpay.reconciliation.discrepancies.total{type}` is incremented (wired in Phase 5; leave a hook).

```bash
./mvnw test -Dtest=ReconciliationEngineIT
```

**Measured (2026-11-01, final 35-test run):** 100,000 records in **3,084 ms** (an earlier warm run measured 1,872 ms); 99,925 matched, 50 amount mismatches, 25 missing internal, 25 missing external; 100 persisted anomalies.

**Exit criteria:** gate green; no clearing file fully buffered in memory; virtual threads bounded by `Semaphore(50)`; anomalies persisted and queryable by run.

**Watch-outs:** 5-second target is sensitive to container CPU and Hikari pool size; measure after warm-up, avoid per-record `SELECT`; `IN (1000 UUIDs)` parameter limits (use `ANY`/named-list expansion); timezone consistency for `business_date`; don't include seed time in the measurement.

---

## 6. Phase 5 — Observability, Chaos Benchmark & Portfolio Packaging

**Objective:** prove the system under load, expose the required metrics, ship a Grafana dashboard, and present the project credibly.

### 6.1 Micrometer metrics

- [x] `LedgerMetrics` component:
  - [x] `sentinelpay.ledger.transfers.total{status,currency}` counter — increment `POSTED`, `INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_ACTIVE`, `IDEMPOTENT_REPLAY`.
  - [x] `sentinelpay.ledger.lock.acquisition.duration` timer — sample around the two `FOR UPDATE` calls only (not the whole transaction).
  - [x] `sentinelpay.outbox.pending.gauge` gauge — `SELECT count(*) FROM outbox_events WHERE status='PENDING'` (indexed; optionally cache via scheduled `AtomicLong` to avoid DB work per scrape).
  - [x] `sentinelpay.reconciliation.discrepancies.total{type}` counter — increment per persisted anomaly.
  - [x] Optional: `sentinelpay.outbox.lag.seconds` (age of oldest pending event) implemented; deadlocks remain visible via PostgreSQL logs.
- [x] `MetricsIT`: perform transfers, hit `/actuator/prometheus`, assert all four metric families appear with expected tags (Prometheus renders dots as underscores).
- [x] Do not create high-cardinality tags (no account IDs, no idempotency keys, no transaction IDs).

### 6.2 Prometheus & Grafana

- [x] `docker/prometheus/prometheus.yml`: scrape the app (`host.docker.internal:8080/actuator/prometheus`) every 5s.
- [x] Grafana provisioning: Prometheus datasource + dashboard provider.
- [x] `docker/grafana/dashboards/sentinelpay.json` panels:
  - [x] Transfer throughput by status/currency.
  - [x] p50/p95/p99 transfer latency.
  - [x] Account lock-acquisition p99.
  - [x] Outbox pending gauge + oldest-event lag.
  - [x] Reconciliation discrepancies by type.
  - [x] JVM heap, live threads/virtual threads, Hikari active/idle connections.
- [x] Compose smoke test: start app + Grafana; confirm panels populate. Captured in [`results/grafana-live.png`](results/grafana-live.png).

### 6.3 Gatling chaos simulation

- [x] Add Gatling Maven plugin + `LedgerChaosSimulation.java` under `src/test/java/com/sentinelpay/gatling/`.
- [x] Test data setup: seed N funded accounts (e.g., 2,000) with generous balances; write a circular CSV feeder (`sourceAccountId`, `destinationAccountId`); unique `Idempotency-Key` per request.
- [x] Scenario mix: 90% `POST /api/v1/transfers`, 10% `GET /api/v1/accounts/{id}/audit`; occasional insufficient-funds/duplicate-key branches to exercise error paths.
- [ ] Load profile: ramp to **2,500 requests/sec** over 60s, hold for 120s; assert p99 < 25 ms, error rate < 0.1%, and achieved throughput ≥ 2,500 rps. **Implemented and run; gate FAILED (2026-11-01):** 300,000 sustained requests, 115,486 succeeded (962 successful transfers/s), 184,514 `503` (61.5%), p99 3,041 ms. Assertion left intact.
- [ ] Run against a documented 4-vCPU environment with explicit container CPU limits; store the report screenshot (not the full HTML report) in the repo. Current evidence is from a documented 4-core / 8-thread host **without cgroup CPU pinning**; see [`results/verification.md`](results/verification.md).
- [x] If the target is missed, tune Hikari pool/DB indexes/worker batch sizes and document the measured result honestly — do not relax the assertion silently. Pools 40/50/60/120 and listener concurrency were measured; storage is the ceiling.

```bash
./mvnw gatling:test -Dgatling.simulationClass=com.sentinelpay.gatling.LedgerChaosSimulation
```

### 6.4 Portfolio README

- [x] Replace the bootstrap README with:
  - [x] One-paragraph problem statement + the three failure modes table.
  - [x] Architecture and transfer-flow diagrams: editable Excalidraw sources + PNG renders in [`docs/diagrams`](diagrams), with Mermaid versions retained in the README.
  - [x] Quick start: `docker compose up -d`, `./mvnw spring-boot:run`, sample `curl` for account creation/transfer/audit.
  - [x] API contract table + error taxonomy link.
  - [x] Test commands and results per phase.
  - [x] Benchmark table: achieved TPS, p50/p95/p99, error rate, outbox lag, reconciliation duration, hardware.
  - [x] ADR links and explicit trade-offs/limitations.
- [x] Commit Grafana dashboard JSON, Gatling simulation, and benchmark evidence. Committed in `b10656b` with the failure report screenshot and storage analysis under [`results/`](results/).
- [x] Final full-suite run: `./mvnw verify` (or document the exact Surefire/Failsafe invocation for `*IT`). **35 tests green in 55.3 s** after adding the audit and dead-letter regressions; full record in [`results/verification.md`](results/verification.md).

### 6.5 Phase 5 gate

- [ ] Gatling report shows ≥ 2,500 rps and p99 < 25 ms on the documented environment. **FAILED:** 962 successful transfers/s, p99 3,041 ms, 61.5% `503`. Evidence and storage analysis: [`results/verification.md`](results/verification.md).
- [x] All four required metrics visible at `/actuator/prometheus`.
- [x] Grafana dashboard renders live data (nine panels captured at [`results/grafana-live.png`](results/grafana-live.png)).
- [ ] README quick start works from a clean clone (verified manually). Commands and response fields were exercised against the running Compose stack; a clean-clone run is pending commit.
- [x] All earlier gates still green in the same build (`./mvnw verify`, 35 tests).

---

## 7. Cross-Phase Test Matrix

| Test class | Phase | Infrastructure | Proves |
| --- | --- | --- | --- |
| `SchemaMigrationIT` | 1 | Postgres | Flyway clean apply, append-only trigger, sign constraints, partial index |
| `LedgerTransferIT` | 2 | Postgres | Double-entry posting, validations, DB idempotency |
| `ReversalAuditIT` | 2 | Postgres | Compensating reversals, derived status, zero drift, cross-account transaction balance proof |
| `LedgerInvariantIT` | 2 | Postgres | `balance_minor = SUM(entries)` for every account |
| `ConcurrencyStressIT` | 2 | Postgres | Overdraft race + deadlock freedom |
| `IdempotencyIT` | 3 | Postgres + Redis | Edge lock, replay, fingerprint mismatch, TTL |
| `KafkaConsumerIT` | 3 | Kafka + Postgres | At-least-once delivery + dedup |
| `OutboxResilienceIT` | 3 | Postgres + Kafka | Broker outage buffering + eventual delivery |
| `OutboxDeadLetterIT` | 3 | Postgres + unreachable Kafka broker | Synchronous metadata failure → bounded retry → `DEAD_LETTER` |
| `ReconciliationEngineIT` | 4 | Postgres | 100k records, 100 anomalies, < 5s |
| `MetricsIT` | 5 | Full stack | Required Micrometer meters |
| `LedgerChaosSimulation` | 5 | Full stack + Gatling | 2,500+ rps at p99 < 25 ms |

**Rule:** never mock Postgres, Redis, Kafka, or `JdbcClient` in these classes. If a test needs an outage, pause/proxy the real container.

---

## 8. Risk Register

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Testcontainers 2.0.3 API/package changes | Build friction | Contain all container setup in `AbstractIntegrationTest`; pin versions; fall back to 1.21.x only with an ADR note (not silently). |
| Kafka container pause is unreliable on CI | Flaky Phase 3 gate | Prefer Toxiproxy for network cut; keep pause as primary with health-checked fallback. |
| 5-second reconciliation on slow CI | False gate failure | Warm-up run, chunk/batch tuning, indexed `ANY` lookups, measure engine only; document hardware. |
| 2,500 rps unreachable on a laptop | Phase 5 gate failure | Run on a documented 4-vCPU environment; tune pools; report measured numbers instead of weakening assertions. |
| Flyway checksum drift after editing V1 | Startup failure | Finalize V1 before first shared application; thereafter add new migrations. |
| `transactions` update vs. append-only ambiguity | Audit inconsistency | Adopt D-1 (derived reversal status) and never mutate the original. |
| System reserve goes negative / funding impossible | Account creation failures | Adopt D-2 (`account_type` + conditional check) and seed a large genesis reserve. |
| Long relay transaction during broker outage | Lock contention/latency | Short send timeout, batch abort on first failure, bounded batch size. |
| Duplicate Kafka delivery after timeout | Double side effects | Consumer dedup table (`processed_kafka_events`) with `ON CONFLICT DO NOTHING`. |
| Refund fails because funds were spent | 422 on reversal | Documented limitation of non-negative user wallets; surface `INSUFFICIENT_FUNDS` with available balance. |
| High-cardinality metrics | Prometheus blow-up | Tag only by status/currency/type; never by IDs. |
| Redis loss wipes completed idempotency records | Duplicate risk after cache loss | DB `UNIQUE(idempotency_key)` remains the durable backstop; Redis is an optimization, not the source of truth. |

---

## 9. Commit & Review Checkpoints

- [x] `docs: capture SentinelPay specification, agent rules, ADRs` (`560d080`)
- [x] `feat(phase-1): scaffold ledger schema, infrastructure, and migration gate` (`37e7ed1`)
- [x] `feat(phase-1): finalize durable schema, infrastructure, and configuration` (`1e446c7`)
- [x] `feat(phase-2): add double-entry ledger, reversals, audit, and REST API` (`96e2b36`)
- [x] `feat(phase-3): add Redis idempotency, outbox relay, and Kafka consumer` (`d1d29ef`)
- [x] `feat(phase-4): add streaming EOD reconciliation engine and API` (`f6bef4b`)
- [x] `feat(phase-5): add outbox metrics, Grafana dashboard, and Gatling benchmark` (`b10656b`)
- [x] `docs: final README, architecture diagrams, and verification record` (this commit)

Phases were committed as cohesive, reviewable changes rather than splitting interdependent classes across commits. Each phase commit compiles; the Phase 5 load gate remains a documented failure rather than a weakened assertion.

---

## 10. Explicit Non-Goals (Post-MVP)

Out of scope unless a later phase explicitly adds it:

- Authentication/authorization, API keys, OAuth2, tenant isolation.
- Real bank/clearinghouse integrations and FX conversion.
- Debezium CDC (polling outbox is the chosen approach).
- Kafka transactions / exactly-once producer semantics (effectively-once is achieved via outbox + dedup).
- Ledger/outbox retention, partitioning, archival (50M-row index behaviour is a design claim, not a tested SLA).
- Kubernetes/Helm manifests, autoscaling, multi-region deployment.
- UI/dashboard beyond Grafana.

---

## 11. Phase Sign-Off Template

Copy this into the phase commit description when the gate passes:

```text
Phase: <n> — <name>
Gate command: <command>
Result: PASS (duration <s>)
Key assertions:
  - <assertion 1>
  - <assertion 2>
Deviations/decisions: <D-x or ADR link, or "none">
Known limitations: <...>
Next phase: <n+1>
```
