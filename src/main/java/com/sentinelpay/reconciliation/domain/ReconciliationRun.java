package com.sentinelpay.reconciliation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationRun(
        UUID runId,
        String fileName,
        String fileSha256,
        LocalDate businessDate,
        String status,
        long totalRecords,
        long matchedCount,
        long amountMismatchCount,
        long missingInternalCount,
        long missingExternalCount,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
}
