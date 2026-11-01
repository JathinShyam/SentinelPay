package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID accountId,
        String ownerId,
        String currency,
        long balanceMinor,
        AccountStatus status,
        AccountType accountType,
        Instant createdAt
) {
    public static AccountView from(Account account) {
        return new AccountView(account.accountId(), account.ownerId(), account.currency(),
                account.balanceMinor(), account.status(), account.accountType(), account.createdAt());
    }
}
