-- End-of-day settlement reconciliation schema (D-3).
-- One run per ingested clearinghouse file. Matched records are counted on the run
-- row only (D-4); the discrepancy table stores anomalies exclusively.

CREATE TABLE reconciliation_runs (
    run_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name               TEXT NOT NULL,
    file_sha256             CHAR(64),
    business_date           DATE NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                            CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    total_records           BIGINT NOT NULL DEFAULT 0 CHECK (total_records >= 0),
    matched_count           BIGINT NOT NULL DEFAULT 0 CHECK (matched_count >= 0),
    amount_mismatch_count   BIGINT NOT NULL DEFAULT 0 CHECK (amount_mismatch_count >= 0),
    missing_internal_count  BIGINT NOT NULL DEFAULT 0 CHECK (missing_internal_count >= 0),
    missing_external_count  BIGINT NOT NULL DEFAULT 0 CHECK (missing_external_count >= 0),
    error_message           TEXT,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- MISSING_EXTERNAL rows describe internal transactions absent from the file,
    -- so they are not bounded by the clearing file's record count.
    CONSTRAINT chk_reconciliation_counts
        CHECK (matched_count + amount_mismatch_count + missing_internal_count
               <= total_records),
    CONSTRAINT chk_reconciliation_completion
        CHECK (status IN ('RUNNING') OR completed_at IS NOT NULL)
);

CREATE INDEX idx_reconciliation_runs_business_date
ON reconciliation_runs (business_date DESC);

CREATE TABLE reconciliation_discrepancies (
    discrepancy_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                  UUID NOT NULL REFERENCES reconciliation_runs(run_id) ON DELETE CASCADE,
    type                    VARCHAR(24) NOT NULL
                            CHECK (type IN ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_INTERNAL', 'MISSING_EXTERNAL')),
    external_ref            VARCHAR(128),
    transaction_id          UUID REFERENCES transactions(transaction_id),
    expected_amount_minor   BIGINT,
    actual_amount_minor     BIGINT,
    currency                CHAR(3),
    details                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reconciliation_identity
        CHECK (external_ref IS NOT NULL OR transaction_id IS NOT NULL)
);

CREATE INDEX idx_reconciliation_discrepancies_run_type
ON reconciliation_discrepancies (run_id, type);

CREATE INDEX idx_reconciliation_discrepancies_transaction
ON reconciliation_discrepancies (transaction_id)
WHERE transaction_id IS NOT NULL;

-- Consumer-side deduplication: at-least-once Kafka delivery becomes effectively-once.
CREATE TABLE processed_kafka_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
