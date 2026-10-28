# TDR-002: Lexicographical UUID Sorting with Pessimistic Row Locking

* **Status:** Accepted
* **Context:** Optimistic locking (`@Version`) causes high transaction abort and retry storms on "hot" merchant accounts receiving hundreds of concurrent payments per second. Standard pessimistic locking (`SELECT ... FOR UPDATE`) risks deadlocks when `Account A → Account B` and `Account B → Account A` execute concurrently.
* **Decision:** Acquire pessimistic row locks using `SELECT ... FOR UPDATE`, but enforce a total ordering across all locks by comparing `sourceAccountId.compareTo(destinationAccountId)` and always locking the smaller UUID first.
* **Consequence:** Eliminates retry storms on hot merchant accounts while guaranteeing zero circular wait conditions in PostgreSQL.

---

## Enforcement in SentinelPay

* Law 3 of the [agent directives](../../SPECIFICATION.md#6-ai-coding-agent-directives-non-negotiable-engineering-laws).
* Reference implementation: `LedgerTransferService.executeTransfer` sorts IDs before calling `accountRepo.findByIdForUpdate(...)` — see [SPECIFICATION.md §4.1](../../SPECIFICATION.md#41-step-a-deadlock-free-double-entry-transfer--atomic-outbox-write).
* Proven by the Phase 2 gate: `ConcurrencyStressIT` Test 2 (bidirectional deadlock proof, 100+100 concurrent threads, zero deadlocks, `driftMinor = 0`).
* The `accounts.version` column is retained for informational/audit purposes, not as the locking mechanism.
