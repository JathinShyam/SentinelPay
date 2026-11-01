package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record ReversalResult(
        UUID transactionId,
        UUID originalTransactionId,
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
