# SentinelPay — Master Technical Specification

> **Status:** Captured design notes — v1 (2026-10-17)
> **Purpose:** Single source of truth for architecture, database schema, API contracts, technical decisions, and the phase-gated build plan.
> **Audience:** Human engineers and AI coding agents (see `CLAUDE.md`, `.cursorrules`, `docs/MASTER_PROMPT.md`).
> **Captured from:** the original project design notes / blueprint, reorganized for clarity. All IDs (`FR-xx`, `TDR-xxx`), tables, DDL, and code snippets are preserved verbatim.

---

## Table of Contents

1. [Overview & Problem Statement](#1-overview--problem-statement)
2. [End-to-End System Architecture & Concurrency Model](#2-end-to-end-system-architecture--concurrency-model)
3. [PostgreSQL Double-Entry & Outbox Schema (DDL)](#3-postgresql-double-entry--outbox-schema-ddl)
4. [Java 21 & Spring Boot Reference Implementation](#4-java-21--spring-boot-reference-implementation)
5. [Two-Tier Idempotency & Consumer Deduplication](#5-two-tier-idempotency--consumer-deduplication)
6. [AI Coding Agent Directives (Non-Negotiable Engineering Laws)](#6-ai-coding-agent-directives-non-negotiable-engineering-laws)
7. [Product Requirements Document (PRD)](#7-product-requirements-document-prd)
8. [Technical Requirements Document (TRD)](#8-technical-requirements-document-trd)
9. [System Architecture Document](#9-system-architecture-document)
10. [Technical Decision Records (ADR / TDR)](#10-technical-decision-records-adr--tdr)
11. [Phased Implementation Roadmap](#11-phased-implementation-roadmap)
12. [Agent Enablement Files](#12-agent-enablement-files)

---

## 1. Overview & Problem Statement

**SentinelPay** separates itself from standard student projects by solving three distributed systems failure modes inside a single cohesive architecture:

1. **Concurrent double-spending** — solved via Redis atomic locks and deterministic database lock ordering.
2. **Floating-point and balance drift** — solved via immutable minor-unit double-entry accounting.
3. **Dual-write inconsistencies** — solved via the Transactional Outbox pattern with `FOR UPDATE SKIP LOCKED`.

The system is a high-throughput, distributed double-entry ledger and end-of-day (EOD) settlement engine designed to guarantee **effectively-once financial accounting** and **zero balance drift** under concurrent load.

---

## 2. End-to-End System Architecture & Concurrency Model

The system is divided into four isolated operational planes so that high-contention ledger writes never block on external network calls or downstream Kafka brokers:

| Architectural Plane | Core Components | Concurrency & Fault-Tolerance Mechanism |
| --- | --- | --- |
| **1. Ingress & Idempotency Layer** | Spring Boot 3.4 Filter, Redis 7 | Rejects duplicate network retries at the edge using atomic `SET key NX EX` locks before touching the database connection pool. |
| **2. Core ACID Ledger Engine** | Java 21 Service Layer, PostgreSQL 16 | Uses **Deterministic Lock Ordering** (sorting account UUIDs before `SELECT ... FOR UPDATE`) to eliminate circular deadlocks during simultaneous bi-directional transfers. |
| **3. Transactional Outbox Relay** | Scheduled Poller (or Debezium CDC), Apache Kafka | Polls uncommitted events in batches of 100 using PostgreSQL `FOR UPDATE SKIP LOCKED`, allowing multiple app replicas to relay events concurrently without lock contention. |
| **4. EOD Settlement Engine** | Java 21 Virtual Threads, Batch Parser | Spawns lightweight virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) to reconcile 100,000+ simulated clearinghouse records against internal ledger snapshots. |

> **Note:** The TRD (see §8) pins the runtime baseline at Spring Boot 3.5.x / 4.1.1 and PostgreSQL 17/18; treat the TRD stack table as authoritative where versions differ from this overview.

### 2.1 The Mathematical Invariant

SentinelPay never stores money as `DOUBLE` or `FLOAT`, and it never updates a balance without recording why. All monetary values are stored in **minor units** (`BIGINT` representing paise or cents, where ₹100.50 is stored as `10050`). Every transaction $T$ must produce $k \ge 2$ journal entries whose signed amounts sum to zero:

$$\sum_{i=1}^{k} \text{EntryAmount}_i = 0$$

> **Review highlights (signals of production-scale design):** Deterministic lock ordering (`compareTo` on UUIDs before `FOR UPDATE`) and the partial index on `outbox_events WHERE status = 'PENDING'`.

---

## 3. PostgreSQL Double-Entry & Outbox Schema (DDL)

### 3.1 `V1__init_ledger_schema.sql`

Place this migration in `src/main/resources/db/migration/V1__init_ledger_schema.sql` (managed by Flyway). It includes a PostgreSQL trigger that enforces **append-only immutability** on journal entries.

```sql
-- Enable UUID v7 / v4 generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. ACCOUNTS: Materialized balance snapshot + Optimistic/Pessimistic lock target
CREATE TABLE accounts (
    account_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        VARCHAR(64) NOT NULL,
    currency        CHAR(3) NOT NULL,
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Prevent negative balances on standard user wallets (system clearing accounts can be exempt via account_type if desired)
    CONSTRAINT chk_non_negative_balance CHECK (balance_minor >= 0)
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id);

-- 2. TRANSACTIONS: Logical envelope for a financial event + Durable Idempotency
CREATE TABLE transactions (
    transaction_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    tx_type         VARCHAR(32) NOT NULL CHECK (tx_type IN ('TRANSFER', 'DEPOSIT', 'PAYOUT', 'FEE', 'REVERSAL')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'REVERSED', 'FAILED')),
    currency        CHAR(3) NOT NULL,
    description     TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. LEDGER ENTRIES: Immutable append-only double-entry journal lines
CREATE TABLE ledger_entries (
    entry_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID NOT NULL REFERENCES transactions(transaction_id),
    account_id            UUID NOT NULL REFERENCES accounts(account_id),
    direction              VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Signed minor units: DEBIT is negative (-10050), CREDIT is positive (+10050)
    signed_amount_minor   BIGINT NOT NULL CHECK (signed_amount_minor <> 0),
    running_balance_minor BIGINT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_direction_sign CHECK (
        (direction = 'DEBIT' AND signed_amount_minor < 0) OR
        (direction = 'CREDIT' AND signed_amount_minor > 0)
    )
);

CREATE INDEX idx_ledger_entries_tx ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_time ON ledger_entries(account_id, created_at DESC);

-- Enforce Append-Only Immutability on ledger_entries (No UPDATE or DELETE allowed)
CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries table is append-only. Use a compensating REVERSAL transaction instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

-- 4. OUTBOX EVENTS: Guarantees zero dual-write data loss between Postgres and Kafka
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(64) NOT NULL,  -- e.g., 'TRANSACTION'
    aggregate_id    UUID NOT NULL,         -- Partition key for Kafka ordering
    event_type      VARCHAR(64) NOT NULL,  -- e.g., 'PaymentPostedEvent'
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PROCESSED', 'DEAD_LETTER')),
    retry_count     INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

-- Partial index ensures polling only scans pending rows, keeping query time under 1ms even with 50M historical rows
CREATE INDEX idx_outbox_pending
ON outbox_events (next_attempt_at ASC, created_at ASC)
WHERE status = 'PENDING';
```

### 3.2 `V2__init_reconciliation_schema.sql` (planned)

> **Note:** The source material designates this migration as "EOD Settlement batches & discrepancy tables" (Phase 1 deliverable) but does not include its DDL yet. It must cover at minimum: an EOD settlement batch/run record (file name, totals, status, timestamps) and per-record discrepancy results with statuses `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, `MISSING_EXTERNAL` (see FR-06 and §9.2 flow 3).

### 3.3 Consumer deduplication table (referenced in §5)

```sql
CREATE TABLE processed_kafka_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ
);
```

---

## 4. Java 21 & Spring Boot Reference Implementation

> **Note:** Section title retained from the source material; the TRD pins Spring Boot 3.5.x / 4.1.1.

### 4.1 Step A: Deadlock-Free Double-Entry Transfer + Atomic Outbox Write

A classic senior backend interview trap is: *"What happens if Account A transfers ₹500 to Account B at the exact same millisecond Account B transfers ₹200 to Account A?"* If Thread 1 locks `A` then `B`, and Thread 2 locks `B` then `A`, PostgreSQL hits a circular deadlock.

SentinelPay prevents this by **sorting the Account UUIDs deterministically** before acquiring pessimistic row locks (`SELECT ... FOR UPDATE`):

```java
package com.sentinelpay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.ledger.domain.*;
import com.sentinelpay.ledger.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerTransferService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final LedgerEntryRepository entryRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public LedgerTransferService(AccountRepository accountRepo,
                                 TransactionRepository txRepo,
                                 LedgerEntryRepository entryRepo,
                                 OutboxRepository outboxRepo,
                                 ObjectMapper objectMapper) {
        this.accountRepo = accountRepo;
        this.txRepo = txRepo;
        this.entryRepo = entryRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    public record TransferRequest(
        String idempotencyKey,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        String currency
    ) {
        public TransferRequest {
            if (amountMinor <= 0) throw new IllegalArgumentException("Transfer amount must be positive");
            if (sourceAccountId.equals(destinationAccountId)) {
                throw new IllegalArgumentException("Source and destination accounts must differ");
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID executeTransfer(TransferRequest req) {
        // 1. Fast idempotency check inside DB (backed by UNIQUE constraint on idempotency_key)
        var existingTx = txRepo.findByIdempotencyKey(req.idempotencyKey());
        if (existingTx.isPresent()) {
            return existingTx.get().getTransactionId();
        }

        // 2. Deterministic Lock Ordering: Always lock the smaller UUID first to prevent deadlocks
        UUID firstLockId = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0
                ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondLockId = firstLockId.equals(req.sourceAccountId())
                ? req.destinationAccountId() : req.sourceAccountId();

        Account firstAccount = accountRepo.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException(firstLockId));
        Account secondAccount = accountRepo.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException(secondLockId));

        Account source = firstLockId.equals(req.sourceAccountId()) ? firstAccount : secondAccount;
        Account destination = firstLockId.equals(req.destinationAccountId()) ? firstAccount : secondAccount;

        // 3. Validate currency and sufficient funds
        if (!source.getCurrency().equals(req.currency()) || !destination.getCurrency().equals(req.currency())) {
            throw new CurrencyMismatchException("Currency mismatch across transfer accounts");
        }
        if (source.getBalanceMinor() < req.amountMinor()) {
            throw new InsufficientFundsException(source.getAccountId(), source.getBalanceMinor(), req.amountMinor());
        }

        // 4. Update materialized balances in memory
        long newSourceBalance = source.getBalanceMinor() - req.amountMinor();
        long newDestBalance = destination.getBalanceMinor() + req.amountMinor();
        source.setBalanceMinor(newSourceBalance);
        destination.setBalanceMinor(newDestBalance);
        accountRepo.saveAll(List.of(source, destination));

        // 5. Persist Transaction envelope
        TransactionEntity tx = txRepo.save(new TransactionEntity(
                UUID.randomUUID(), req.idempotencyKey(), "TRANSFER", "POSTED", req.currency()
        ));

        // 6. Append balanced Debit (-amount) and Credit (+amount) journal entries (Sum == 0)
        LedgerEntry debitEntry = new LedgerEntry(
                UUID.randomUUID(), tx.getTransactionId(), source.getAccountId(),
                "DEBIT", -req.amountMinor(), newSourceBalance
        );
        LedgerEntry creditEntry = new LedgerEntry(
                UUID.randomUUID(), tx.getTransactionId(), destination.getAccountId(),
                "CREDIT", req.amountMinor(), newDestBalance
        );
        entryRepo.saveAll(List.of(debitEntry, creditEntry));

        // 7. Write domain event to Transactional Outbox in the SAME ACID transaction
        String payloadJson = serializeEventPayload(tx.getTransactionId(), req, newSourceBalance, newDestBalance);
        OutboxEvent outboxEvent = new OutboxEvent(
                UUID.randomUUID(),
                "TRANSACTION",
                tx.getTransactionId(), // Used as Kafka partition key to preserve ordering
                "TransferPostedEvent",
                payloadJson
        );
        outboxRepo.save(outboxEvent);

        return tx.getTransactionId();
    }

    private String serializeEventPayload(UUID txId, TransferRequest req, long srcBal, long destBal) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "transactionId", txId,
                "sourceAccountId", req.sourceAccountId(),
                "destinationAccountId", req.destinationAccountId(),
                "amountMinor", req.amountMinor(),
                "currency", req.currency(),
                "sourceBalanceAfter", srcBal,
                "destinationBalanceAfter", destBal,
                "occurredAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
```

### 4.2 Step B: Concurrent Outbox Relay Using `FOR UPDATE SKIP LOCKED`

Instead of relying on a single-threaded poller that becomes a bottleneck, SentinelPay uses PostgreSQL's `FOR UPDATE SKIP LOCKED` clause. If you run **4 instances** of SentinelPay in Kubernetes, Instance 1 locks rows 1–100, and Instance 2 immediately skips rows 1–100 and locks rows 101–200 with zero waiting.

```java
package com.sentinelpay.ledger.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayWorker {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate txTemplate;

    public OutboxRelayWorker(JdbcTemplate jdbcTemplate,
                             KafkaTemplate<String, String> kafkaTemplate,
                             TransactionTemplate txTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.txTemplate = txTemplate;
    }

    public record PendingOutboxRow(UUID eventId, UUID aggregateId, String eventType, String payload, int retryCount) {}

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-interval-ms:200}")
    public void pollAndPublishBatch() {
        txTemplate.executeWithoutResult(status -> {
            // Claim up to 100 pending events without blocking other application nodes
            String claimSql = """
                SELECT event_id, aggregate_id, event_type, payload::text, retry_count
                FROM outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= NOW()
                ORDER BY created_at ASC
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """;

            List<PendingOutboxRow> batch = jdbcTemplate.query(claimSql, (rs, rowNum) -> new PendingOutboxRow(
                    UUID.fromString(rs.getString("event_id")),
                    UUID.fromString(rs.getString("aggregate_id")),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("retry_count")
            ));

            for (PendingOutboxRow event : batch) {
                try {
                    // Use aggregateId (transactionId) as Kafka key to guarantee partition ordering
                    kafkaTemplate.send("ledger.transactions.v1", event.aggregateId().toString(), event.payload())
                                 .get(3, TimeUnit.SECONDS);

                    jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET status = 'PROCESSED', processed_at = NOW()
                        WHERE event_id = ?
                        """, event.eventId());

                } catch (Exception ex) {
                    handlePublishFailure(event);
                }
            }
        });
    }

    private void handlePublishFailure(PendingOutboxRow event) {
        int nextRetry = event.retryCount() + 1;
        if (nextRetry >= 5) {
            jdbcTemplate.update("UPDATE outbox_events SET status = 'DEAD_LETTER', retry_count = ? WHERE event_id = ?",
                    nextRetry, event.eventId());
        } else {
            // Exponential backoff: 2^retryCount seconds
            long backoffSeconds = (long) Math.pow(2, nextRetry);
            Timestamp nextAttempt = Timestamp.from(Instant.now().plusSeconds(backoffSeconds));
            jdbcTemplate.update("""
                UPDATE outbox_events
                SET retry_count = ?, next_attempt_at = ?
                WHERE event_id = ?
                """, nextRetry, nextAttempt, event.eventId());
        }
    }
}
```

---

## 5. Two-Tier Idempotency & Consumer Deduplication

Even with the Transactional Outbox, remember that `OutboxRelayWorker` guarantees **at-least-once delivery** to Kafka (for example, if Kafka acknowledges the message, but the PostgreSQL node crashes milliseconds before `SET status = 'PROCESSED'` commits, the worker will republish the message on recovery).

To make the entire pipeline **effectively-once**:

1. **Ingress Idempotency (API Edge):** Before hitting `LedgerTransferService`, a Spring filter checks Redis using `SET idemp:{Idempotency-Key} IN_PROGRESS NX EX 30`. If the key already exists with `IN_PROGRESS`, return `HTTP 409 Conflict` immediately. Once the DB transaction commits, cache the serialized response JSON in Redis for 24 hours.
2. **Downstream Consumer Idempotency (Settlement / Notification Consumers):** Any Kafka consumer reading `ledger.transactions.v1` inserts the incoming `event_id` into a `processed_kafka_events(event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ)` table inside its own database transaction before executing side effects.

---

## 6. AI Coding Agent Directives (Non-Negotiable Engineering Laws)

> When generating code for **SentinelPay**, the AI assistant must obey these six non-negotiable engineering laws (also enforced via `CLAUDE.md` / `.cursorrules`):

1. **Zero Floating-Point Math:** Never use `float`, `double`, or `BigDecimal` for storage or wire transfer of monetary values. All monetary values across REST DTOs, Java domain objects, PostgreSQL columns, and Kafka events must be signed 64-bit integers (`long` / `BIGINT`) representing **minor units** (e.g., `10050` = ₹100.50 or $100.50).
2. **Strict Double-Entry Invariant:** Never mutate `accounts.balance_minor` without simultaneously inserting at least two rows into `ledger_entries` (one `DEBIT` less than 0, one `CREDIT` greater than 0) within the exact same `@Transactional` boundary whose `signed_amount_minor` values sum to exactly `0`.
3. **Deterministic Lock Ordering:** Whenever locking two or more `accounts` rows via `SELECT ... FOR UPDATE`, always sort the target `UUID` values in ascending lexicographical order before acquiring locks to make circular database deadlocks mathematically impossible.
4. **No Dual Writes:** Never call `KafkaTemplate.send()` inside an HTTP request thread or inside a ledger `@Transactional` method. All domain events must be persisted to the `outbox_events` table in the same PostgreSQL transaction and relayed asynchronously via `FOR UPDATE SKIP LOCKED`.
5. **Append-Only Ledger:** Never generate `UPDATE` or `DELETE` SQL statements against `ledger_entries` or `transactions`. Corrections and refunds must be executed as new compensating `REVERSAL` transactions.
6. **Real Infrastructure Testing:** Do not mock `AccountRepository`, `JdbcTemplate`, Redis, or Kafka in integration tests. All integration tests must run against real ephemeral containers using **Testcontainers**.

---

## 7. Product Requirements Document (PRD)

### 7.1 Product Vision & Problem Statement

Payment processors and fintech platforms fail under high concurrency when network retries trigger duplicate charges, race conditions allow negative wallet balances, or application crashes between database commits and message broker publishes cause lost settlement events. **SentinelPay** is a high-throughput, distributed double-entry ledger and end-of-day (EOD) settlement engine designed to guarantee **effectively-once financial accounting** and **zero balance drift** under concurrent load.

### 7.2 Target Personas & Consumers

* **Merchant API Clients:** External services initiating wallet creations, deposits, peer-to-peer transfers, and refunds over REST.
* **Downstream Settlement & Risk Services:** Event consumers subscribing to `ledger.transactions.v1` on Apache Kafka for fraud scoring, user notifications, and clearinghouse reporting.
* **FinOps & Compliance Auditors:** Internal operators triggering EOD clearinghouse file reconciliations and auditing historical account balance proofs.

### 7.3 Functional Requirements (FR)

| ID | Feature | Requirement Description | Acceptance Criteria |
| --- | --- | --- | --- |
| **FR-01** | **Account Lifecycle Management** | Create and query multi-currency accounts (`INR`, `USD`, `EUR`) with status controls (`ACTIVE`, `FROZEN`, `CLOSED`). | Creating an account initializes `balance_minor = 0` and `version = 0`. Frozen or closed accounts reject all debit/credit transfers with `HTTP 422`. |
| **FR-02** | **Idempotent Atomic Transfers** | Execute funds transfers between two accounts of the same currency using a mandatory `Idempotency-Key` header. | Concurrent duplicate requests with the same `Idempotency-Key` return the original `transactionId` (`HTTP 200`) or `HTTP 409 Conflict` if currently in-flight; never double-debiting. |
| **FR-03** | **Compensating Reversals** | Reverse a previously `POSTED` transaction cleanly without mutating historical journal lines. | Creates a new `REVERSAL` transaction with inverse `DEBIT` and `CREDIT` ledger entries and marks the original transaction status as `REVERSED` idempotently. |
| **FR-04** | **Immutable Audit & Balance Proof** | Provide an audit endpoint for any account that recalculates its balance from historical `ledger_entries` and compares it against `accounts.balance_minor`. | Returns `driftMinor = 0` and verifies $\sum \text{DEBIT} + \sum \text{CREDIT} = 0$ across all linked transactions. |
| **FR-05** | **Guaranteed Event Streaming** | Publish every committed ledger state change to Apache Kafka (`ledger.transactions.v1`) via the Transactional Outbox pattern. | 100% of committed database transactions arrive on Kafka even if the Kafka broker experiences a 30-second outage during the HTTP request. |
| **FR-06** | **Virtual-Thread EOD Settlement** | Ingest simulated external bank clearing CSV/NDJSON files (100,000+ rows) and reconcile against internal transactions. | Identifies `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, and `MISSING_EXTERNAL` records in under 5 seconds using Java 21+ Virtual Threads. |

### 7.4 Non-Functional Requirements (NFRs & SLAs)

* **Consistency:** Strict ACID serializability on account balances; zero discrepancy between `accounts.balance_minor` and `SUM(ledger_entries.signed_amount_minor)`.
* **Throughput & Latency:** Sustain at least **2,500 concurrent transfers/sec** on a single 4-vCPU node with **p99 latency less than 25ms** (excluding chaos faults).
* **Availability & Recovery:** Automatic exponential backoff (`2^retry_count` seconds) on outbox relay failures, routing poison pills to `DEAD_LETTER` status after 5 failed attempts.

---

## 8. Technical Requirements Document (TRD)

### 8.1 Pinned Technology Stack (2026 Baseline)

| Layer | Technology | Pinned Version | Rationale |
| --- | --- | --- | --- |
| **Language** | Java (OpenJDK / Temurin) | **Java 21 LTS** (or Java 25) | Virtual Threads (`JEP 444`), Records, Pattern Matching, Sealed Interfaces. |
| **Framework** | Spring Boot | **3.5.x** or **4.1.1** | Native Virtual Thread support (`spring.threads.virtual.enabled=true`), Micrometer OpenTelemetry, JdbcClient. |
| **Primary Database** | PostgreSQL | **17 / 18** (19 RC compatible) | Row-level locking (`FOR UPDATE`), `SKIP LOCKED` queue polling, PL/pgSQL immutability triggers, partial indexes. |
| **Migrations** | Flyway | **10.x+** | Deterministic SQL schema versioning (`V1__`, `V2__`). |
| **Edge Lock & Cache** | Redis | **7.4+** | Atomic `SET key value NX EX` in-flight idempotency locking and response caching. |
| **Event Streaming** | Apache Kafka (KRaft mode) | **3.8+** | ZooKeeper-less high-throughput event log partitioned by `transaction_id`. |
| **Testing & Load** | Testcontainers + Gatling | **Testcontainers 2.0.3**, **Gatling 3.11+** | Ephemeral Dockerized integration testing and high-concurrency stress benchmarking. |
| **Observability** | Micrometer + Prometheus + Grafana | **Latest** | Custom counters for ledger throughput, lock wait time, and outbox lag. |

### 8.2 REST API Contracts

#### `POST /api/v1/transfers`

* **Required Headers:**
  * `Idempotency-Key`: `String` (UUID or 16–128 char unique client token)
  * `Content-Type`: `application/json`

* **Request Payload:**

```json
{
  "sourceAccountId": "01929a3b-4c10-7000-8000-000000000001",
  "destinationAccountId": "01929a3b-4c10-7000-8000-000000000002",
  "amountMinor": 50000,
  "currency": "INR",
  "description": "Merchant order settlement #ORD-9981"
}
```

* **Response `201 Created` (or `200 OK` on idempotent replay):**

```json
{
  "transactionId": "01929a3c-9981-7111-9000-123456789abc",
  "idempotencyKey": "idem-client-req-88219",
  "status": "POSTED",
  "amountMinor": 50000,
  "currency": "INR",
  "sourceBalanceMinorAfter": 100000,
  "destinationBalanceMinorAfter": 90000,
  "createdAt": "2026-10-17T16:20:00Z"
}
```

#### Additional Core Endpoints

* `POST /api/v1/accounts` → Creates an account (`ownerId`, `currency`, `initialDepositMinor` via system clearing account).
* `POST /api/v1/transactions/{transactionId}/reverse` → Executes a compensating double-entry reversal (`Idempotency-Key` required).
* `GET /api/v1/accounts/{accountId}/audit` → Computes live sum of `ledger_entries` vs `accounts.balance_minor` and returns an integrity certificate.
* `POST /api/v1/reconciliation/run` → Accepts an NDJSON/CSV clearing file and runs the Java Virtual Thread reconciliation batch, returning a summary report ID.

### 8.3 Error Taxonomy (RFC 7807 `ProblemDetail`)

| HTTP Status | Error Code | Trigger Condition | Client Action |
| --- | --- | --- | --- |
| `400 Bad Request` | `INVALID_AMOUNT` | `amountMinor` less than or equal to 0, or `sourceAccountId == destinationAccountId`. | Do not retry; fix request payload. |
| `404 Not Found` | `ACCOUNT_NOT_FOUND` | Either `sourceAccountId` or `destinationAccountId` does not exist. | Do not retry; verify account UUIDs. |
| `409 Conflict` | `IDEMPOTENCY_IN_FLIGHT` | Another request with the same `Idempotency-Key` is currently executing, or payload hash mismatches an existing key. | Back off 200ms and retry with the same key. |
| `422 Unprocessable` | `INSUFFICIENT_FUNDS` | `source.balance_minor` is less than `req.amountMinor`. | Do not retry until account is funded. |
| `422 Unprocessable` | `CURRENCY_MISMATCH` | Source, destination, or request currency codes do not match. | Do not retry. |

### 8.4 Custom Observability Metrics (Micrometer)

The application must register and expose these exact metrics at `/actuator/prometheus`:

* `sentinelpay.ledger.transfers.total{status, currency}` (Counter)
* `sentinelpay.ledger.lock.acquisition.duration` (Timer)
* `sentinelpay.outbox.pending.gauge` (Gauge tracking `SELECT count(*) FROM outbox_events WHERE status = 'PENDING'`)
* `sentinelpay.reconciliation.discrepancies.total{type}` (Counter)

---

## 9. System Architecture Document

### 9.1 Repository & Package Structure

The AI coding agent must organize the project using a modular package-by-feature layout:

```text
sentinelpay/
├── docker-compose.yml                 # Postgres 17/18, Redis 7, Kafka (KRaft), Prometheus, Grafana
├── pom.xml                            # Java 21+, Spring Boot, Flyway, Testcontainers 2.0.3, Gatling
├── docs/
│   └── adr/                           # TDR-001 through TDR-005 markdown files
├── src/main/resources/
│   ├── application.yml                # Virtual threads enabled, HikariCP tuning, Kafka config
│   └── db/migration/
│       ├── V1__init_ledger_schema.sql # Accounts, Transactions, LedgerEntries, OutboxEvents + Trigger
│       └── V2__init_reconciliation_schema.sql # EOD Settlement batches & discrepancy tables
├── src/main/java/com/sentinelpay/
│   ├── SentinelPayApplication.java
│   ├── common/
│   │   ├── exception/                 # GlobalExceptionHandler (RFC 7807 ProblemDetail)
│   │   └── idempotency/               # RedisIdempotencyFilter & Aspect
│   ├── ledger/
│   │   ├── api/                       # TransferController, AccountController, AuditController
│   │   ├── domain/                    # Account, TransactionEntity, LedgerEntry (Java Records/Entities)
│   │   ├── repository/                # JdbcClient / Spring Data repositories with FOR UPDATE queries
│   │   └── service/                   # LedgerTransferService, ReversalService, LedgerAuditService
│   ├── outbox/
│   │   ├── domain/                    # OutboxEvent
│   │   ├── worker/                    # OutboxRelayWorker (FOR UPDATE SKIP LOCKED poller)
│   │   └── consumer/                  # Downstream SettlementKafkaConsumer (with deduplication table)
│   └── reconciliation/
│       ├── api/                       # ReconciliationController
│       ├── domain/                    # ClearingRecord, DiscrepancyReport
│       └── service/                   # VirtualThreadReconciliationEngine
└── src/test/java/com/sentinelpay/
    ├── AbstractIntegrationTest.java   # Singleton Testcontainers setup (Postgres, Redis, Kafka)
    ├── ConcurrencyStressIT.java       # 100-thread cyclic transfer & overdraft race condition proof
    ├── OutboxResilienceIT.java        # Pauses Kafka container mid-flight, verifies eventual delivery
    └── gatling/
        └── LedgerChaosSimulation.java # Gatling load simulation
```

### 9.2 Critical Execution Data Flows

1. **Hot Transfer Flow (Synchronous ACID Path):**
   `HTTP POST /api/v1/transfers` → `RedisIdempotencyFilter` (`SET idemp:{key} IN_FLIGHT NX EX 30`) → `LedgerTransferService` → Sort `sourceAccountId` and `destinationAccountId` lexicographically → `SELECT ... FOR UPDATE` on both accounts → Validate currency & balance → Update `accounts.balance_minor` → Insert `transactions` row → Insert two `ledger_entries` rows (`-amount`, `+amount`) → Insert `outbox_events` row (`PENDING`) → `COMMIT` PostgreSQL transaction → Cache HTTP response in Redis (`EX 86400`) → Return `201 Created`.

2. **Asynchronous Outbox Relay Flow:**
   `OutboxRelayWorker` (runs every `200ms` across $N$ stateless instances) → `SELECT ... FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= NOW() ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED` → Publish each event to Kafka topic `ledger.transactions.v1` using `aggregate_id` (`transaction_id`) as the partition key → Batch update `status = 'PROCESSED', processed_at = NOW()` → `COMMIT`.

3. **EOD Virtual-Thread Reconciliation Flow:**
   `POST /api/v1/reconciliation/run` → Stream clearinghouse file in chunks of 1,000 records → Submit each chunk to `Executors.newVirtualThreadPerTaskExecutor()` → Fetch matching internal `transactions` batch via `WHERE transaction_id = ANY(:ids)` → Compare `amount_minor`, `currency`, and `status` → Persist `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, or `MISSING_EXTERNAL` rows into `reconciliation_discrepancies`.

---

## 10. Technical Decision Records (ADR / TDR)

Full documents live in [`docs/adr/`](docs/adr/):

| ID | Title | File |
| --- | --- | --- |
| TDR-001 | Storing Monetary Values as Signed `BIGINT` Minor Units | [`docs/adr/TDR-001-minor-unit-bigint.md`](docs/adr/TDR-001-minor-unit-bigint.md) |
| TDR-002 | Lexicographical UUID Sorting with Pessimistic Row Locking | [`docs/adr/TDR-002-deterministic-lock-ordering.md`](docs/adr/TDR-002-deterministic-lock-ordering.md) |
| TDR-003 | Polling Transactional Outbox with `FOR UPDATE SKIP LOCKED` vs. Two-Phase Commit (XA) | [`docs/adr/TDR-003-transactional-outbox-vs-xa.md`](docs/adr/TDR-003-transactional-outbox-vs-xa.md) |
| TDR-004 | Database-Level Immutability Triggers on `ledger_entries` | [`docs/adr/TDR-004-append-only-ledger-trigger.md`](docs/adr/TDR-004-append-only-ledger-trigger.md) |
| TDR-005 | Java 21+ Virtual Threads (`JEP 444`) for I/O-Heavy EOD Reconciliation | [`docs/adr/TDR-005-virtual-threads-reconciliation.md`](docs/adr/TDR-005-virtual-threads-reconciliation.md) |

### 10.1 TDR-001: Storing Monetary Values as Signed `BIGINT` Minor Units

* **Context:** Floating-point representations (`IEEE 754`) cannot accurately represent base-10 decimals like `0.10`, and `NUMERIC(19,4)` incurs slower CPU arithmetic and serialization ambiguity across JSON/Kafka consumers.
* **Decision:** Store and transmit all amounts as 64-bit signed integers (`BIGINT` / Java `long`) in the currency's smallest minor unit (paise/cents), accompanied by an ISO-4217 3-character currency code.
* **Consequence:** Zero rounding errors, fast integer ALU operations in PostgreSQL and Java, and a maximum representable balance of $\pm 9.22 \times 10^{16}$ major currency units.

### 10.2 TDR-002: Lexicographical UUID Sorting with Pessimistic Row Locking

* **Context:** Optimistic locking (`@Version`) causes high transaction abort and retry storms on "hot" merchant accounts receiving hundreds of concurrent payments per second. Standard pessimistic locking (`SELECT ... FOR UPDATE`) risks deadlocks when `Account A → Account B` and `Account B → Account A` execute concurrently.
* **Decision:** Acquire pessimistic row locks using `SELECT ... FOR UPDATE`, but enforce a total ordering across all locks by comparing `sourceAccountId.compareTo(destinationAccountId)` and always locking the smaller UUID first.
* **Consequence:** Eliminates retry storms on hot merchant accounts while guaranteeing zero circular wait conditions in PostgreSQL.

### 10.3 TDR-003: Polling Transactional Outbox with `FOR UPDATE SKIP LOCKED` vs. Two-Phase Commit (XA)

* **Context:** Distributed XA transactions across PostgreSQL and Kafka severely degrade throughput and availability. Publishing directly to Kafka inside a database transaction risks either phantom events (if the DB rolls back after Kafka acks) or lost events (if the app crashes after DB commit before Kafka acks).
* **Decision:** Write outbox rows inside the local PostgreSQL transaction and poll them using a partial index (`WHERE status = 'PENDING'`) and `FOR UPDATE SKIP LOCKED`.
* **Consequence:** Sub-millisecond overhead on the payment hot path, zero external CDC infrastructure required for local spin-up, and lock-free horizontal scaling across multiple worker pods.

### 10.4 TDR-004: Database-Level Immutability Triggers on `ledger_entries`

* **Context:** Application bugs or manual database maintenance could accidentally mutate or delete historical journal rows, invalidating regulatory audits.
* **Decision:** Attach a PostgreSQL `BEFORE UPDATE OR DELETE` trigger (`trg_immutable_ledger`) on `ledger_entries` that raises a fatal database exception.
* **Consequence:** Even with root SQL credentials, historical journal entries cannot be altered through standard DML; all adjustments require an auditable `REVERSAL` transaction.

### 10.5 TDR-005: Java 21+ Virtual Threads (`JEP 444`) for I/O-Heavy EOD Reconciliation

* **Context:** Reconciling 100,000+ bank settlement lines requires thousands of concurrent database batch lookups and file I/O operations. Platform thread pools either exhaust OS memory or bottleneck on thread pool queues.
* **Decision:** Enable `spring.threads.virtual.enabled=true` and run EOD settlement chunk workers on `Executors.newVirtualThreadPerTaskExecutor()`, bounded by a `Semaphore(50)` to protect the HikariCP database connection pool.
* **Consequence:** High I/O concurrency with minimal heap overhead while preventing connection pool starvation.

---

## 11. Phased Implementation Roadmap

Execute one phase at a time, running the verification command at the end of each phase before moving to the next. (Master prompt: [`docs/MASTER_PROMPT.md`](docs/MASTER_PROMPT.md); detailed task checklist and pre-flight decisions: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).)

### Phase 1: Foundation, Docker Topology & Immutable Schema

* **Scope:**
  1. Generate `pom.xml` (Java 21, Spring Boot, Spring Data JDBC / `JdbcClient`, Flyway, Redis, Kafka, Micrometer, Testcontainers 2.0.3).
  2. Create `docker-compose.yml` for PostgreSQL, Redis, and Apache Kafka (KRaft mode).
  3. Implement `V1__init_ledger_schema.sql` (Accounts, Transactions, LedgerEntries, OutboxEvents, Partial Index, and `trg_immutable_ledger`) and `V2__init_reconciliation_schema.sql`.
* **Definition of Done (Verification):**
  Run `./mvnw test -Dtest=SchemaMigrationIT` using Testcontainers to verify Flyway applies cleanly and attempting an `UPDATE` on `ledger_entries` throws a PostgreSQL exception.

### Phase 2: Core Double-Entry Engine & Concurrency Proof

* **Scope:**
  1. Implement `AccountController` and `SystemClearingSeeder` (creates an initial `SYSTEM_RESERVE` account so user wallets can be funded via double-entry transfers rather than raw balance edits).
  2. Implement `LedgerTransferService` (UUID lock sorting, balance checks, double-entry journal insertion, outbox insertion).
  3. Implement `ReversalService` and `LedgerAuditService` (`GET /api/v1/accounts/{id}/audit`).
* **Definition of Done (Verification):**
  Run `./mvnw test -Dtest=ConcurrencyStressIT`:
  * **Test 1 (Overdraft Race):** Account A has `10000` minor units. Fire 50 concurrent threads each attempting to transfer `1000` minor units to Account B. Exactly 10 must succeed (`balance = 0`) and 40 must fail with `InsufficientFundsException`.
  * **Test 2 (Bidirectional Deadlock Proof):** Fire 100 concurrent threads transferring `100` minor units `A → B` and 100 concurrent threads transferring `100` minor units `B → A` simultaneously. Verify zero deadlocks and `audit` endpoint reports `driftMinor = 0`.

### Phase 3: Two-Tier Idempotency & Transactional Outbox Relay

* **Scope:**
  1. Build `RedisIdempotencyFilter` handling `Idempotency-Key` lock acquisition (`SET NX EX`), payload SHA-256 fingerprint validation, and cached response replay.
  2. Build `OutboxRelayWorker` with `FOR UPDATE SKIP LOCKED`, exponential backoff, and `DEAD_LETTER` transition.
  3. Build `SettlementKafkaConsumer` with idempotent deduplication (`processed_kafka_events` table).
* **Definition of Done (Verification):**
  Run `./mvnw test -Dtest=OutboxResilienceIT`: Pause the Kafka Testcontainer, execute 20 transfers, verify 20 rows sit in `outbox_events` with `retry_count > 0`, unpause the Kafka container, and assert all 20 events arrive on `ledger.transactions.v1` within 5 seconds.

### Phase 4: Java 21 Virtual-Thread EOD Reconciliation Engine

* **Scope:**
  1. Create a synthetic clearing file generator that outputs 100,000 settlement lines matching internal transactions, intentionally injecting 50 `AMOUNT_MISMATCH`, 25 `MISSING_INTERNAL`, and 25 `MISSING_EXTERNAL` anomalies.
  2. Implement `VirtualThreadReconciliationEngine` using `Executors.newVirtualThreadPerTaskExecutor()` and `Semaphore`-bounded batch queries.
* **Definition of Done (Verification):**
  Run `./mvnw test -Dtest=ReconciliationEngineIT` and verify all 100 injected discrepancies out of 100,000 records are isolated and persisted in under 5 seconds.

### Phase 5: Chaos Benchmarking, Observability & Portfolio Packaging

* **Scope:**
  1. Wire Micrometer custom metrics (`sentinelpay.ledger.transfers.total`, `sentinelpay.outbox.pending.gauge`) and include a pre-built Grafana dashboard JSON in `/docker/grafana/dashboards/`.
  2. Write `LedgerChaosSimulation.java` in Gatling to ramp up to 2,500+ requests/sec and export the HTML report.
  3. Write the top-level `README.md` with architecture diagrams, ADR links, and Gatling latency percentiles.
* **Definition of Done (Verification):**
  Run Gatling stress test confirming 2,500+ TPS at p99 latency under 25ms.

---

## 12. Agent Enablement Files

| File | Purpose |
| --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | Engineering invariants & tech-stack compliance for Claude Code (and any Claude-based agent). |
| [`.cursorrules`](.cursorrules) | Same constraints, auto-loaded by Cursor. |
| [`docs/MASTER_PROMPT.md`](docs/MASTER_PROMPT.md) | Copy-paste master execution prompt enforcing the 5-phase, verification-gated build. |
| [`docs/adr/`](docs/adr/) | TDR-001 through TDR-005 as standalone markdown records. |
