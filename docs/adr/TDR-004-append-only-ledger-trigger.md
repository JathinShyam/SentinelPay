# TDR-004: Database-Level Immutability Triggers on `ledger_entries`

* **Status:** Accepted
* **Context:** Application bugs or manual database maintenance could accidentally mutate or delete historical journal rows, invalidating regulatory audits.
* **Decision:** Attach a PostgreSQL `BEFORE UPDATE OR DELETE` trigger (`trg_immutable_ledger`) on `ledger_entries` that raises a fatal database exception.
* **Consequence:** Even with root SQL credentials, historical journal entries cannot be altered through standard DML; all adjustments require an auditable `REVERSAL` transaction.

---

## Enforcement in SentinelPay

* Law 5 of the [agent directives](../../SPECIFICATION.md#6-ai-coding-agent-directives-non-negotiable-engineering-laws): no `UPDATE`/`DELETE` SQL against `ledger_entries` or `transactions`.
* Trigger function `prevent_ledger_mutation()` raises: *"ledger_entries table is append-only. Use a compensating REVERSAL transaction instead."*
* Corrections: `POST /api/v1/transactions/{transactionId}/reverse` creates a new `REVERSAL` transaction with inverse `DEBIT`/`CREDIT` entries. The original transaction row is never mutated; its effective status is derived as `REVERSED` from the unique `reversal_of` link (D-1, FR-03).
* Proven by the Phase 1 gate: `SchemaMigrationIT` asserts an `UPDATE` on `ledger_entries` throws a PostgreSQL exception.
