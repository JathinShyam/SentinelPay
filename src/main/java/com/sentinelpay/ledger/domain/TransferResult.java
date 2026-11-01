package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record TransferResult(
        UUID transactionId,
        String idempotencyKey,
        String status,
        long amountMinor,
        String currency,
        long sourceBalanceMinorAfter,
        long destinationBalanceMinorAfter,
        Instant createdAt,
        boolean replay
) {
}
