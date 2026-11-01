package com.sentinelpay.common.exception;

import java.util.UUID;

public class TransactionNotFoundException extends ApiException {

    public TransactionNotFoundException(UUID transactionId) {
        super(ApiErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction %s does not exist".formatted(transactionId),
                java.util.Map.of("transactionId", transactionId.toString()));
    }
}
