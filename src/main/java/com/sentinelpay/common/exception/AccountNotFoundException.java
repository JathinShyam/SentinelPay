package com.sentinelpay.common.exception;

import java.util.Map;
import java.util.UUID;

public class AccountNotFoundException extends ApiException {

    public AccountNotFoundException(UUID accountId) {
        super(ApiErrorCode.ACCOUNT_NOT_FOUND, "Account %s does not exist".formatted(accountId),
                Map.of("accountId", accountId.toString()));
    }
}
