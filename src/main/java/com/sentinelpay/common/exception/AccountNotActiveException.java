package com.sentinelpay.common.exception;

import java.util.Map;
import java.util.UUID;

public class AccountNotActiveException extends ApiException {

    public AccountNotActiveException(UUID accountId, String status) {
        super(ApiErrorCode.ACCOUNT_NOT_ACTIVE,
                "Account %s is %s; only ACTIVE accounts can move funds".formatted(accountId, status),
                Map.of("accountId", accountId.toString(), "status", status));
    }
}
