-- Enable UUID generation (gen_random_uuid)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. ACCOUNTS: materialized balance snapshot + pessimistic lock target.
-- D-2: system clearing/equity accounts are explicitly allowed to carry a negative
-- balance so user wallets can be funded through real double-entry postings.
CREATE TABLE accounts (
    account_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id        VARCHAR(64) NOT NULL,
    currency        CHAR(3) NOT NULL,
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    account_type    VARCHAR(20) NOT NULL DEFAULT 'USER'
                    CHECK (account_type IN ('USER', 'SYSTEM')),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_non_negative_balance
        CHECK (account_type = 'SYSTEM' OR balance_minor >= 0)
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id);
CREATE INDEX idx_accounts_type ON accounts(account_type);

-- 2. TRANSACTIONS: logical envelope for a financial event + durable idempotency.
-- D-1: the original row is never mutated. A reversal links back through
-- reversal_of (UNIQUE makes double-reversal impossible even under a race).
CREATE TABLE transactions (
    transaction_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    tx_type         VARCHAR(32) NOT NULL CHECK (tx_type IN ('TRANSFER', 'DEPOSIT', 'PAYOUT', 'FEE', 'REVERSAL')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'REVERSED', 'FAILED')),
    currency        CHAR(3) NOT NULL,
    description     TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    reversal_of     UUID UNIQUE REFERENCES transactions(transaction_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reversal_type
        CHECK ((tx_type = 'REVERSAL' AND reversal_of IS NOT NULL)
            OR (tx_type <> 'REVERSAL' AND reversal_of IS NULL))
);

CREATE INDEX idx_transactions_reversal_of ON transactions(reversal_of)
    WHERE reversal_of IS NOT NULL;
CREATE INDEX idx_transactions_status_created ON transactions(status, created_at);

-- 3. LEDGER ENTRIES: immutable append-only double-entry journal lines.
CREATE TABLE ledger_entries (
    entry_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID NOT NULL REFERENCES transactions(transaction_id),
    account_id            UUID NOT NULL REFERENCES accounts(account_id),
    direction             VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Signed minor units: DEBIT is negative (-10050), CREDIT is positive (+10050).
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

-- Enforce append-only immutability on ledger_entries (no UPDATE or DELETE allowed).
CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries table is append-only. Use a compensating REVERSAL transaction instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

-- 4. OUTBOX EVENTS: guarantees zero dual-write data loss between Postgres and Kafka.
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PROCESSED', 'DEAD_LETTER')),
    retry_count     INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

-- Partial index keeps polling proportional to the pending backlog, not table history.
CREATE INDEX idx_outbox_pending
ON outbox_events (next_attempt_at ASC, created_at ASC)
WHERE status = 'PENDING';
