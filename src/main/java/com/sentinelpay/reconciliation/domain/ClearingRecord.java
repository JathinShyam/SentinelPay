package com.sentinelpay.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One settlement line from an external clearinghouse file.
 */
public record ClearingRecord(
        String externalRef,
        UUID transactionId,
        long amountMinor,
        String currency,
        String status,
        Instant settledAt
) {
}
