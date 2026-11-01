package com.sentinelpay.common.exception;

import java.util.Map;
import java.util.UUID;

public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(UUID accountId, long availableMinor, long requestedMinor) {
        super(ApiErrorCode.INSUFFICIENT_FUNDS,
                "Account %s has %d minor units available but %d requested"
                        .formatted(accountId, availableMinor, requestedMinor),
                Map.of(
                        "accountId", accountId.toString(),
                        "availableMinor", availableMinor,
                        "requestedMinor", requestedMinor));
    }
}
