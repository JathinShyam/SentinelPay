# TDR-004: Database-Level Immutability Triggers on Ledger Tables

* **Status:** Accepted; amended 2026-11-05 to cover `transactions`
* **Context:** Application bugs or manual database maintenance could accidentally mutate or delete historical journal rows, invalidating regulatory audits. The original trigger covered only `ledger_entries`; an unreferenced `transactions` envelope could still be deleted or rewritten.
* **Decision:** Attach PostgreSQL `BEFORE UPDATE OR DELETE` triggers that raise a fatal database exception: `trg_immutable_ledger` on `ledger_entries` (V1) and `trg_immutable_transactions` on `transactions` (V3).
* **Consequence:** Even with root SQL credentials, historical journal entries and transaction envelopes cannot be altered or deleted through standard DML; all adjustments require an auditable `REVERSAL` transaction.

---

## Enforcement in SentinelPay

* Law 5 of the [agent directives](../../SPECIFICATION.md#6-ai-coding-agent-directives-non-negotiable-engineering-laws): no `UPDATE`/`DELETE` SQL against `ledger_entries` or `transactions`.
* Trigger functions `prevent_ledger_mutation()` and `prevent_transaction_mutation()` raise: *"… table is append-only. Use a compensating REVERSAL transaction instead."*
* Corrections: `POST /api/v1/transactions/{transactionId}/reverse` creates a new `REVERSAL` transaction with inverse `DEBIT`/`CREDIT` entries. The original transaction row is never mutated; its effective status is derived as `REVERSED` from the unique `reversal_of` link (D-1, FR-03).
* Proven by the Phase 1 gate: `SchemaMigrationIT` asserts that `UPDATE`/`DELETE` on `ledger_entries` and `UPDATE`/`DELETE` on `transactions` throw a PostgreSQL exception.
