# TDR-001: Storing Monetary Values as Signed `BIGINT` Minor Units

* **Status:** Accepted
* **Context:** Floating-point representations (`IEEE 754`) cannot accurately represent base-10 decimals like `0.10`, and `NUMERIC(19,4)` incurs slower CPU arithmetic and serialization ambiguity across JSON/Kafka consumers.
* **Decision:** Store and transmit all amounts as 64-bit signed integers (`BIGINT` / Java `long`) in the currency's smallest minor unit (paise/cents), accompanied by an ISO-4217 3-character currency code.
* **Consequence:** Zero rounding errors, fast integer ALU operations in PostgreSQL and Java, and a maximum representable balance of $\pm 9.22 \times 10^{16}$ major currency units.

---

## Enforcement in SentinelPay

* Law 1 of the [agent directives](../../SPECIFICATION.md#6-ai-coding-agent-directives-non-negotiable-engineering-laws): no `float`, `double`, or `BigDecimal` anywhere money is stored or transmitted.
* Columns: `accounts.balance_minor`, `ledger_entries.signed_amount_minor`, `ledger_entries.running_balance_minor` — all `BIGINT`.
* Wire formats (REST DTOs, Kafka payloads) use `amountMinor`, `sourceBalanceMinorAfter`, `destinationBalanceMinorAfter`, etc.
