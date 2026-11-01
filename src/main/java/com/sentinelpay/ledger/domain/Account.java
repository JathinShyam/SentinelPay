package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID accountId,
        String ownerId,
        String currency,
        long balanceMinor,
        AccountType accountType,
        AccountStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public Account withBalance(long newBalanceMinor) {
        return new Account(accountId, ownerId, currency, newBalanceMinor, accountType, status,
                version, createdAt, updatedAt);
    }

    public boolean isSystem() {
        return accountType == AccountType.SYSTEM;
    }
}
