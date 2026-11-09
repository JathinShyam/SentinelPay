package com.sentinelpay.wallet.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionDetailView(
        UUID transactionId,
        String type,
        String status,
        String currency,
        String description,
        UUID reversalOf,
        UUID reversedBy,
        Instant createdAt,
        List<EntryView> entries
) {
    public record EntryView(
            UUID entryId,
            UUID accountId,
            String direction,
            long amountMinor,
            Long runningBalanceMinor,
            Instant createdAt
    ) {
    }
}
