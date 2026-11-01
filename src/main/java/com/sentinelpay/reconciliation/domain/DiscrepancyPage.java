package com.sentinelpay.reconciliation.domain;

import java.util.List;

public record DiscrepancyPage(
        java.util.UUID runId,
        int page,
        int size,
        long total,
        List<ReconciliationDiscrepancy> items
) {
}
