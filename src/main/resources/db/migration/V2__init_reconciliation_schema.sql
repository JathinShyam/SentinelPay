-- End-of-day settlement reconciliation schema.
-- A run represents one ingested clearinghouse file. Every external line and every
-- internal-only transaction materializes exactly one result row, including MATCHED,
-- so the run can be audited without replaying the source file.

CREATE TABLE reconciliation_runs (
    run_id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_file_name             TEXT NOT NULL,
    source_file_sha256           CHAR(64),
    status                       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    total_records                BIGINT NOT NULL DEFAULT 0 CHECK (total_records >= 0),
    matched_records              BIGINT NOT NULL DEFAULT 0 CHECK (matched_records >= 0),
    discrepancy_records          BIGINT NOT NULL DEFAULT 0 CHECK (discrepancy_records >= 0),
    total_external_amount_minor  BIGINT NOT NULL DEFAULT 0,
    started_at                   TIMESTAMPTZ,
    completed_at                 TIMESTAMPTZ,
    failure_reason               TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reconciliation_counts
        CHECK (matched_records + discrepancy_records <= total_records),
    CONSTRAINT chk_reconciliation_completion
        CHECK (status IN ('PENDING', 'RUNNING') OR completed_at IS NOT NULL)
);

CREATE INDEX idx_reconciliation_runs_status
ON reconciliation_runs (status, created_at DESC);

CREATE TABLE reconciliation_discrepancies (
    discrepancy_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                  UUID NOT NULL REFERENCES reconciliation_runs(run_id) ON DELETE CASCADE,
    external_reference      VARCHAR(128),
    internal_transaction_id UUID REFERENCES transactions(transaction_id),
    status                  VARCHAR(24) NOT NULL
                            CHECK (status IN ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_INTERNAL', 'MISSING_EXTERNAL')),
    external_amount_minor   BIGINT,
    internal_amount_minor   BIGINT,
    currency                CHAR(3),
    details                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reconciliation_identity
        CHECK (external_reference IS NOT NULL OR internal_transaction_id IS NOT NULL)
);

CREATE INDEX idx_reconciliation_discrepancies_run_status
ON reconciliation_discrepancies (run_id, status);

CREATE INDEX idx_reconciliation_discrepancies_internal_tx
ON reconciliation_discrepancies (internal_transaction_id)
WHERE internal_transaction_id IS NOT NULL;

-- Anomalies are the hot path for FinOps review; matched rows stay out of the index.
CREATE INDEX idx_reconciliation_discrepancies_unmatched
ON reconciliation_discrepancies (run_id, created_at ASC)
WHERE status <> 'MATCHED';
