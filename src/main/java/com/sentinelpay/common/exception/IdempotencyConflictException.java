package com.sentinelpay.common.exception;

import java.util.Map;

public class IdempotencyConflictException extends ApiException {

    public IdempotencyConflictException(String idempotencyKey, String detail) {
        super(ApiErrorCode.IDEMPOTENCY_CONFLICT, detail,
                Map.of("idempotencyKey", idempotencyKey));
    }
}
