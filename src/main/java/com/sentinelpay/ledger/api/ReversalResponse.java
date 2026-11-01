package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.ReversalResult;

import java.time.Instant;
import java.util.UUID;

public record ReversalResponse(
        UUID transactionId,
        UUID originalTransactionId,
        String idempotencyKey,
        String status,
        long amountMinor,
        String currency,
        long sourceBalanceMinorAfter,
        long destinationBalanceMinorAfter,
        Instant createdAt
) {
    public static ReversalResponse from(ReversalResult result) {
        return new ReversalResponse(result.transactionId(), result.originalTransactionId(),
                result.idempotencyKey(), result.status(), result.amountMinor(), result.currency(),
                result.sourceBalanceMinorAfter(), result.destinationBalanceMinorAfter(),
                result.createdAt());
    }
}
