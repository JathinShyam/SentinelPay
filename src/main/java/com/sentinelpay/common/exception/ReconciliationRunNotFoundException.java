package com.sentinelpay.common.exception;

import java.util.Map;
import java.util.UUID;

public class ReconciliationRunNotFoundException extends ApiException {

    public ReconciliationRunNotFoundException(UUID runId) {
        super(ApiErrorCode.RECONCILIATION_RUN_NOT_FOUND,
                "Reconciliation run %s does not exist".formatted(runId),
                Map.of("runId", runId.toString()));
    }
}
