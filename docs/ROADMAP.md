# SentinelPay roadmap

**Status as of 2026-11-05:** the MVP is complete and published. Phase 5's functional and
observability work is done; the only unmet gate is the 2,500 rps / p99 < 25 ms load target, which
remains documented and unweakened in [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md#65-phase-5-gate).

This roadmap separates committed follow-ups from production features that are deliberately outside
the MVP ([`IMPLEMENTATION_PLAN.md` §10](IMPLEMENTATION_PLAN.md#10-explicit-non-goals-post-mvp)).

## 1. Finish the current phase cleanly

| Item | Why it matters | Exit criteria | Status |
| --- | --- | --- | --- |
| Immutable `transactions` envelope | The journal was guarded, but an unreferenced transaction row could still be deleted or rewritten. | `V3__immutable_transactions.sql` rejects `UPDATE`/`DELETE`; `SchemaMigrationIT` proves it. | ✅ Done |
| cgroup-limited benchmark | Current numbers come from an unpinned 4-core / 8-thread laptop, so CPU and storage effects are mixed. | Run the unchanged Gatling simulation with explicit 4-vCPU container limits; store the report screenshot. | ✅ Done 2026-11-05 — 4.0 vCPU budget, 431 successful/s, p99 3,224 ms; gate still fails. |
| Load-gate decision | The assertion must not be silently relaxed; either the target is met on suitable hardware or the target is revised by a recorded decision. | Dedicated-host attempt with a separate load generator, or an ADR that supersedes the target with measured evidence. | Open |

The cgroup run is recorded in [`results/verification.md`](results/verification.md#4-vcpu-container-limited-run)
with the [report screenshot](results/gatling-cgroup-4vcpu.png) and
[CPU-limit override](../docker/benchmark/docker-compose.cpu-limits.yml).

## 2. Performance and durability

The measured ceiling is storage, not application code: `pg_test_fsync` reported roughly 450 fsync/s
and 891 fdatasync/s, and the closed-loop probe reached about 929 rps with p99 73 ms. Experiments
worth running, in order:

1. Dedicated NVMe host with the load generator on a separate machine.
2. Group commit or batched posting so one fsync covers several transfers.
3. Reduce fsyncs per transfer across the envelope, entries, balances and outbox inserts.
4. Re-tune HikariCP, Tomcat and virtual-thread settings only on that hardware.
5. Consider durability trade-offs only through an ADR. `synchronous_commit=on` and `fsync=on` stay
   the default.

Do not change [`LedgerChaosSimulation`](../src/test/java/com/sentinelpay/gatling/LedgerChaosSimulation.java)
to make the gate pass.

## 3. Production hardening

These are the highest-value items before any real deployment:

- Authentication and authorization, API keys, OAuth2 and tenant isolation.
- Retention and partitioning for `outbox_events` and `processed_kafka_events`; ledger and
  transaction history stay permanent.
- Alerting and SLOs on top of the existing Prometheus metrics and Grafana dashboard.
- Secrets management, TLS, and API-access audit logging.
- Kubernetes/Helm manifests, autoscaling and multi-region deployment.
- Debezium CDC if polling the outbox stops being acceptable, and Kafka transactions if
  exactly-once producer semantics become a requirement.

## 4. Product and ledger extensions

- A receivable/chargeback flow for reversals where the destination has already spent the funds
  (today this correctly returns `422 INSUFFICIENT_FUNDS`).
- Reconciliation beyond internal `POSTED` transactions, plus operator workflows for anomalies.
- Multi-currency accounts and FX conversion.
- Statements, exports, pagination and an operator UI beyond Grafana.
- Fraud scoring and transaction limits.

## 5. Deliberately deferred

The MVP does not include real bank/clearinghouse integrations, FX, a full UI, Kubernetes, or
multi-region operations. Those stay out until a later phase explicitly adds them; see
[`IMPLEMENTATION_PLAN.md` §10](IMPLEMENTATION_PLAN.md#10-explicit-non-goals-post-mvp) for the
authoritative list.
