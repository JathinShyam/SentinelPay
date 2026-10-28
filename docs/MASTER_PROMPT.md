# SentinelPay — Master Execution Prompt (for AI Coding Agents)

> **How to use:** Paste the prompt below into an AI coding assistant (Claude Code, Cursor, GitHub Copilot Workspace, or any agentic harness) **after** placing `SPECIFICATION.md`, `CLAUDE.md`, and `.cursorrules` in the repository root. It instructs the agent to execute the build across 5 structured phases, running verification tests at each gate and stopping for confirmation before advancing.

---

You are an expert Principal Distributed Systems Engineer and Java 21 Architect. Your task is to build "SentinelPay"—a production-grade, highly concurrent double-entry payment ledger and EOD settlement engine—based strictly on the engineering principles, DDL schemas, and architectural rules defined in our project documentation (`SPECIFICATION.md`) and `.cursorrules` / `CLAUDE.md`.

You must build this project IN STRICT PHASES. Do not skip ahead or generate the entire codebase in one single shot. For each phase, you must:

1. Generate all required production and test files.
2. Execute the verification test command using `./mvnw test`.
3. Stop and output a confirmation summary once the test gate passes successfully before prompting me to proceed to the next phase.

Here is your 5-Phase Execution Plan:

---

### PHASE 1: Foundation, Docker Topology & Immutable Schema

- **Goal:** Set up Maven (`pom.xml` with Java 21, Spring Boot, Flyway, Testcontainers 2.0.3, Redis, Kafka drivers), `docker-compose.yml`, `application.yml`, and Flyway migration scripts `V1__init_ledger_schema.sql` (Accounts, Transactions, LedgerEntries, OutboxEvents, partial index, and `trg_immutable_ledger` trigger) and `V2__init_reconciliation_schema.sql`.
- **Verification Gate:** Run `./mvnw test -Dtest=SchemaMigrationIT`. Verify Flyway applies cleanly and attempting an `UPDATE` or `DELETE` on `ledger_entries` raises a PostgreSQL exception.

### PHASE 2: Core Double-Entry Engine & Concurrency Proof

- **Goal:** Implement domain models, repositories using `JdbcClient`, `LedgerTransferService` (implementing lexicographical UUID lock sorting for deadlock prevention and strict minor-unit balancing), `ReversalService`, and `LedgerAuditService`.
- **Verification Gate:** Run `./mvnw test -Dtest=ConcurrencyStressIT`. Verify that running 100 concurrent conflicting transfer threads results in zero deadlocks, zero balance drift, and correct overdraft rejections.

### PHASE 3: Two-Tier Idempotency & Transactional Outbox Relay

- **Goal:** Implement `RedisIdempotencyFilter` (using atomic `SET NX EX` locks and payload hash validation), `OutboxRelayWorker` (using PostgreSQL `FOR UPDATE SKIP LOCKED` and exponential backoff), and `SettlementKafkaConsumer` with deduplication.
- **Verification Gate:** Run `./mvnw test -Dtest=OutboxResilienceIT`. Pause the Kafka Testcontainer during transaction commits, verify outbox persistence, unpause, and confirm eventual delivery to Kafka within 5 seconds.

### PHASE 4: Java 21 Virtual-Thread EOD Reconciliation Engine

- **Goal:** Implement synthetic clearing file ingestion and `VirtualThreadReconciliationEngine` using Java 21 `Executors.newVirtualThreadPerTaskExecutor()` and `Semaphore`-bounded batch lookups to isolate discrepancies (`MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, `MISSING_EXTERNAL`).
- **Verification Gate:** Run `./mvnw test -Dtest=ReconciliationEngineIT`. Verify reconciliation of 100,000 clearing records with 100 injected anomalies completes in under 5 seconds.

### PHASE 5: Observability & Gatling Chaos Benchmarking

- **Goal:** Wire custom Micrometer Prometheus meters (`sentinelpay.ledger.transfers.total`, `sentinelpay.outbox.pending.gauge`), include Grafana dashboard JSON, write Gatling load simulation `LedgerChaosSimulation.java`, and produce the top-level `README.md` with architecture diagrams and benchmark metrics.
- **Verification Gate:** Run Gatling stress test confirming 2,500+ TPS at p99 latency under 25ms.

---

Begin Phase 1 now. Generate the `pom.xml`, `docker-compose.yml`, `application.yml`, Flyway SQL migrations, and `SchemaMigrationIT`. Do not generate code for Phase 2 yet.
