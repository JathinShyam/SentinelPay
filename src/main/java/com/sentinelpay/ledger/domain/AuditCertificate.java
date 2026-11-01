package com.sentinelpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

public record AuditCertificate(
        UUID accountId,
        long materializedBalanceMinor,
        long ledgerBalanceMinor,
        long driftMinor,
        long entryCount,
        boolean transactionsBalanced,
        Instant checkedAt
) {
}
