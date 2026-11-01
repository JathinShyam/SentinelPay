package com.sentinelpay.common.exception;

import java.util.Map;

public class InvalidAmountException extends ApiException {

    public InvalidAmountException(String detail) {
        super(ApiErrorCode.INVALID_AMOUNT, detail);
    }

    public InvalidAmountException(String detail, Map<String, Object> properties) {
        super(ApiErrorCode.INVALID_AMOUNT, detail, properties);
    }
}
