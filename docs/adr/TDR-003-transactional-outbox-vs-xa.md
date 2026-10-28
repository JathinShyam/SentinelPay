# TDR-003: Polling Transactional Outbox with `FOR UPDATE SKIP LOCKED` vs. Two-Phase Commit (XA)

* **Status:** Accepted
* **Context:** Distributed XA transactions across PostgreSQL and Kafka severely degrade throughput and availability. Publishing directly to Kafka inside a database transaction risks either phantom events (if the DB rolls back after Kafka acks) or lost events (if the app crashes after DB commit before Kafka acks).
* **Decision:** Write outbox rows inside the local PostgreSQL transaction and poll them using a partial index (`WHERE status = 'PENDING'`) and `FOR UPDATE SKIP LOCKED`.
* **Consequence:** Sub-millisecond overhead on the payment hot path, zero external CDC infrastructure required for local spin-up, and lock-free horizontal scaling across multiple worker pods.

---

## Enforcement in SentinelPay

* Law 4 of the [agent directives](../../SPECIFICATION.md#6-ai-coding-agent-directives-non-negotiable-engineering-laws): no `KafkaTemplate.send()` in HTTP request threads or ledger transactions.
* Schema: `outbox_events` table with partial index `idx_outbox_pending` on `(next_attempt_at ASC, created_at ASC) WHERE status = 'PENDING'`.
* Relay: `OutboxRelayWorker` claims batches of 100 every 200ms with `FOR UPDATE SKIP LOCKED`; Kafka partition key = `aggregate_id` (`transaction_id`) to preserve per-transaction ordering; exponential backoff `2^retry_count` seconds; `DEAD_LETTER` after 5 failed attempts.
* Delivery semantics: **at-least-once** → made **effectively-once** by two-tier idempotency (Redis ingress + `processed_kafka_events` consumer dedup). See [SPECIFICATION.md §5](../../SPECIFICATION.md#5-two-tier-idempotency--consumer-deduplication).
* Alternative considered: Debezium CDC (noted as an option in the architecture table, not required for local spin-up).
