-- Enable UUID generation (gen_random_uuid)
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
    direction             VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
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

-- 5. CONSUMER DEDUPLICATION: Makes at-least-once Kafka delivery effectively-once.
-- Inserted in the same local transaction as the consumer's side effects.
CREATE TABLE processed_kafka_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
