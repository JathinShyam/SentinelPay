package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        UUID entryId,
        UUID transactionId,
        UUID accountId,
        Direction direction,
        long signedAmountMinor,
        long runningBalanceMinor,
        Instant createdAt
) {
}
