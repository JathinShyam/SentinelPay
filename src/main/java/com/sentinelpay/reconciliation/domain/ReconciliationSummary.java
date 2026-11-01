package com.sentinelpay.reconciliation.domain;

import java.util.UUID;

public record ReconciliationSummary(
        UUID runId,
        String status,
        long totalRecords,
        long matchedCount,
        long amountMismatchCount,
        long missingInternalCount,
        long missingExternalCount,
        long durationMs
) {
    public static ReconciliationSummary from(ReconciliationRun run, long durationMs) {
        return new ReconciliationSummary(run.runId(), run.status(), run.totalRecords(),
                run.matchedCount(), run.amountMismatchCount(), run.missingInternalCount(),
                run.missingExternalCount(), durationMs);
    }
}
