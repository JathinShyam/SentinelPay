package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.TransferResult;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        String idempotencyKey,
        String status,
        long amountMinor,
        String currency,
        long sourceBalanceMinorAfter,
        long destinationBalanceMinorAfter,
        Instant createdAt
) {
    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(result.transactionId(), result.idempotencyKey(), result.status(),
                result.amountMinor(), result.currency(), result.sourceBalanceMinorAfter(),
                result.destinationBalanceMinorAfter(), result.createdAt());
    }
}
