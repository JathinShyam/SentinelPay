package com.sentinelpay.common.exception;

import java.util.Map;
import java.util.UUID;

public class ReversalNotAllowedException extends ApiException {

    public ReversalNotAllowedException(UUID transactionId, String reason) {
        super(ApiErrorCode.REVERSAL_NOT_ALLOWED,
                "Transaction %s cannot be reversed: %s".formatted(transactionId, reason),
                Map.of("transactionId", transactionId.toString()));
    }
}
