package com.sentinelpay.wallet.api;

import com.sentinelpay.ledger.repository.LedgerEntryRepository.AccountActivityRow;

import java.time.Instant;
import java.util.UUID;

/**
 * One side of a posted transaction, as seen from a single account.
 * Amounts stay in integer minor units; the browser only formats them.
 */
public record ActivityItem(
        UUID entryId,
        UUID transactionId,
        String type,
        String status,
        String direction,
        long amountMinor,
        String currency,
        long balanceAfterMinor,
        UUID counterpartyAccountId,
        String description,
        Instant createdAt
) {
    public static ActivityItem from(AccountActivityRow row) {
        return new ActivityItem(
                row.entryId(),
                row.transactionId(),
                row.type(),
                row.status(),
                row.direction().name(),
                Math.abs(row.signedAmountMinor()),
                row.currency(),
                row.runningBalanceMinor(),
                row.counterpartyAccountId(),
                row.description(),
                row.createdAt());
    }
}
