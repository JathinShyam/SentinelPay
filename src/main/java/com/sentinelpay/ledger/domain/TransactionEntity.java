package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record TransactionEntity(
        UUID transactionId,
        String idempotencyKey,
        TxType txType,
        TxStatus status,
        String currency,
        String description,
        String metadata,
        UUID reversalOf,
        Instant createdAt
) {
    public boolean isReversed() {
        return status == TxStatus.REVERSED;
    }
}
