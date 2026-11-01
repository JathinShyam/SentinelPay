package com.sentinelpay.ledger.domain;

import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.exception.ApiException;
import com.sentinelpay.common.exception.InvalidAmountException;

import java.util.UUID;

public record TransferRequest(
        String idempotencyKey,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        String currency,
        String description
) {

    public TransferRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key header is required");
        }
        if (sourceAccountId == null || destinationAccountId == null) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "sourceAccountId and destinationAccountId are required");
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new InvalidAmountException("Source and destination accounts must differ");
        }
        if (amountMinor <= 0) {
            throw new InvalidAmountException("amountMinor must be greater than zero");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "currency must be a three-letter ISO-4217 code");
        }
    }
}
