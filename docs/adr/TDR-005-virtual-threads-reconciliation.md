# TDR-005: Java 21+ Virtual Threads (`JEP 444`) for I/O-Heavy EOD Reconciliation

* **Status:** Accepted
* **Context:** Reconciling 100,000+ bank settlement lines requires thousands of concurrent database batch lookups and file I/O operations. Platform thread pools either exhaust OS memory or bottleneck on thread pool queues.
* **Decision:** Enable `spring.threads.virtual.enabled=true` and run EOD settlement chunk workers on `Executors.newVirtualThreadPerTaskExecutor()`, bounded by a `Semaphore(50)` to protect the HikariCP database connection pool.
* **Consequence:** High I/O concurrency with minimal heap overhead while preventing connection pool starvation.

---

## Enforcement in SentinelPay

* `VirtualThreadReconciliationEngine` streams clearinghouse files in chunks of 1,000 records and submits each chunk to a virtual-thread-per-task executor (see data flow 3 in [SPECIFICATION.md §9.2](../../SPECIFICATION.md#92-critical-execution-data-flows)).
* Internal batch lookups use `WHERE transaction_id = ANY(:ids)` and classify records as `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, or `MISSING_EXTERNAL` (FR-06).
* Proven by the Phase 4 gate: `ReconciliationEngineIT` reconciles 100,000 records with 100 injected anomalies in under 5 seconds.
* Note: bounded concurrency matters — the `Semaphore(50)` is what keeps virtual threads from stampeding the HikariCP pool.
