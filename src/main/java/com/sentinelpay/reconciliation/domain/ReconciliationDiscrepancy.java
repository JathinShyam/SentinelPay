package com.sentinelpay.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationDiscrepancy(
        UUID discrepancyId,
        UUID runId,
        DiscrepancyType type,
        String externalRef,
        UUID transactionId,
        Long expectedAmountMinor,
        Long actualAmountMinor,
        String currency,
        String details,
        Instant createdAt
) {
}
