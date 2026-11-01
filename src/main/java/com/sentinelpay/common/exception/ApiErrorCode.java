package com.sentinelpay.common.exception;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "Invalid amount"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),
    RECONCILIATION_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "Reconciliation run not found"),
    IDEMPOTENCY_IN_FLIGHT(HttpStatus.CONFLICT, "Idempotent request already in flight"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key payload conflict"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Currency mismatch"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Account is not active"),
    REVERSAL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "Reversal not allowed"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required dependency is unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ApiErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
